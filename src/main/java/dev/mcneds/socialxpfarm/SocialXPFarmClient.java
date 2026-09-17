package dev.mcneds.socialxpfarm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class SocialXPFarmClient implements ClientModInitializer {
    public static final String MOD_ID = "socialxpfarm";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("socialxpfarm.json");

    // Standard container menus append the player's 27 inventory + 9 hotbar slots after the GUI slots.
    private static final int PLAYER_INVENTORY_SLOT_COUNT = 36;

    private Config config;
    private State state = State.MONITORING;
    private int timer;
    private int nonGuestTicks;
    private int menuSeenTicks;
    private boolean warnedUnconfigured;
    private boolean loggedVisitCandidates;

    @Override
    public void onInitializeClient() {
        config = loadConfig();
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        LOGGER.info("SocialXPFarm loaded. Config: {}", CONFIG_PATH.toAbsolutePath());
    }

    private void tick(Minecraft client) {
        if (config == null || !config.enabled || !config.isConfigured()) {
            if (!warnedUnconfigured && client.player != null) {
                warnedUnconfigured = true;
                LOGGER.warn("SocialXPFarm is idle until targetPlayer and profileName are set in {}", CONFIG_PATH.toAbsolutePath());
            }
            return;
        }

        if (!isOnHypixel(client) || client.player == null || client.getConnection() == null) {
            resetState();
            return;
        }

        if (isGuesting(client)) {
            if (state != State.MONITORING) {
                LOGGER.info("Guesting restored; recovery complete.");
            }
            state = State.MONITORING;
            timer = 0;
            nonGuestTicks = 0;
            menuSeenTicks = 0;
            loggedVisitCandidates = false;
            return;
        }

        switch (state) {
            case MONITORING -> {
                nonGuestTicks++;
                if (nonGuestTicks >= config.guestExitGraceTicks) {
                    recover(client);
                }
            }
            case WAITING_FOR_SKYBLOCK -> {
                if (isInSkyBlock(client)) {
                    sendVisit(client);
                } else if (--timer <= 0) {
                    sendPlaySkyBlock(client);
                }
            }
            case WAITING_FOR_MENU -> {
                if (tryClickConfiguredProfile(client)) {
                    state = State.WAITING_FOR_TRANSFER;
                    timer = config.transferTimeoutTicks;
                } else if (--timer <= 0) {
                    scheduleRetry(client, "visit menu did not become usable");
                }
            }
            case WAITING_FOR_TRANSFER -> {
                if (--timer <= 0) {
                    scheduleRetry(client, "server transfer did not complete");
                }
            }
            case RETRY_DELAY -> {
                if (--timer <= 0) {
                    recover(client);
                }
            }
        }
    }

    private void recover(Minecraft client) {
        nonGuestTicks = 0;
        if (isInSkyBlock(client)) {
            sendVisit(client);
        } else {
            sendPlaySkyBlock(client);
        }
    }

    private void sendPlaySkyBlock(Minecraft client) {
        closeHandledScreen(client);
        LOGGER.info("Not in SkyBlock; sending /play sb before retrying visit.");
        if (client.getConnection() != null) {
            client.getConnection().sendCommand("play sb");
        }
        state = State.WAITING_FOR_SKYBLOCK;
        timer = config.skyBlockJoinTimeoutTicks;
    }

    private void sendVisit(Minecraft client) {
        closeHandledScreen(client);
        LOGGER.info("Sending /visit {}", config.targetPlayer);
        if (client.getConnection() != null) {
            client.getConnection().sendCommand("visit " + config.targetPlayer);
        }
        state = State.WAITING_FOR_MENU;
        timer = config.visitMenuTimeoutTicks;
        menuSeenTicks = 0;
        loggedVisitCandidates = false;
    }

    private boolean tryClickConfiguredProfile(Minecraft client) {
        if (!(client.gui.screen() instanceof AbstractContainerScreen<?> handled) || client.gameMode == null || client.player == null) {
            menuSeenTicks = 0;
            return false;
        }

        String title = handled.getTitle().getString().toLowerCase(Locale.ROOT);
        String target = config.targetPlayer.toLowerCase(Locale.ROOT);
        if (!title.contains("visit") || !title.contains(target)) {
            menuSeenTicks = 0;
            return false;
        }

        menuSeenTicks++;
        if (menuSeenTicks < config.menuSettleTicks) {
            return false;
        }

        AbstractContainerMenu menu = handled.getMenu();
        String wantedProfile = config.profileName.trim().toLowerCase(Locale.ROOT);

        // Hypixel's visit GUI is a normal container. Player inventory slots are appended at the end,
        // so only inspect the GUI-owned slots and never click a head from the player's inventory.
        int guiSlotCount = menu.slots.size() > PLAYER_INVENTORY_SLOT_COUNT
                ? menu.slots.size() - PLAYER_INVENTORY_SLOT_COUNT
                : menu.slots.size();

        Slot onlyVisitHead = null;
        int visitHeadCount = 0;
        StringBuilder candidates = new StringBuilder();

        for (int i = 0; i < guiSlotCount; i++) {
            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !stack.is(Items.PLAYER_HEAD)) {
                continue;
            }

            String itemName = stack.getHoverName().getString().trim();
            boolean isVisitHead = itemName.toLowerCase(Locale.ROOT).contains("visit player island")
                    || loreContains(stack, "click to visit");
            if (!isVisitHead) {
                continue;
            }

            visitHeadCount++;
            onlyVisitHead = slot;

            String profile = readProfileFromLore(stack);
            if (candidates.length() > 0) candidates.append(", ");
            candidates.append("slot ").append(slot.index).append("=")
                    .append(profile == null ? "<unknown profile>" : profile);

            if (profile != null && profile.equalsIgnoreCase(config.profileName.trim())) {
                return clickVisitHead(client, menu, slot, profile, false);
            }

            // Also accept the configured profile text anywhere in lore in case Hypixel changes
            // the exact "Profile: ..." formatting but preserves the visible profile name.
            if (loreContains(stack, wantedProfile)) {
                return clickVisitHead(client, menu, slot, config.profileName, false);
            }
        }

        // If Hypixel only offers one visitable profile, selecting the sole valid visit head is safe
        // even if the lore format changes. Never use this fallback when multiple visit heads exist.
        if (visitHeadCount == 1 && onlyVisitHead != null) {
            return clickVisitHead(client, menu, onlyVisitHead, config.profileName, true);
        }

        if (!loggedVisitCandidates) {
            loggedVisitCandidates = true;
            if (visitHeadCount == 0) {
                LOGGER.warn("Visit menu detected, but no visit player-head items were found in its {} GUI slots.", guiSlotCount);
            } else {
                LOGGER.warn("Visit menu detected, but configured profile '{}' did not match candidates: {}",
                        config.profileName, candidates);
            }
        }

        return false;
    }

    private boolean clickVisitHead(Minecraft client, AbstractContainerMenu menu, Slot slot, String profile, boolean fallback) {
        if (fallback) {
            LOGGER.info("Clicking the only visitable player head (slot {}) as fallback for profile '{}'.", slot.index, profile);
        } else {
            LOGGER.info("Clicking SkyBlock profile '{}' in visit menu (slot {}).", profile, slot.index);
        }
        client.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.PICKUP, client.player);
        return true;
    }

    private static String readProfileFromLore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return null;
        }

        for (Component line : lore.lines()) {
            String text = line.getString().trim();
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.startsWith("profile:")) {
                String profile = text.substring(text.indexOf(':') + 1).trim();
                return profile.isEmpty() ? null : profile;
            }
        }
        return null;
    }

    private static boolean loreContains(ItemStack stack, String wantedText) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null || wantedText == null || wantedText.isBlank()) {
            return false;
        }

        String wanted = wantedText.toLowerCase(Locale.ROOT);
        for (Component line : lore.lines()) {
            if (line.getString().toLowerCase(Locale.ROOT).contains(wanted)) {
                return true;
            }
        }
        return false;
    }

    private void scheduleRetry(Minecraft client, String reason) {
        LOGGER.warn("Recovery attempt failed: {}. Retrying shortly.", reason);
        closeHandledScreen(client);
        state = State.RETRY_DELAY;
        timer = config.retryDelayTicks;
        menuSeenTicks = 0;
        loggedVisitCandidates = false;
    }

    private static void closeHandledScreen(Minecraft client) {
        if (client.player != null && client.gui.screen() instanceof AbstractContainerScreen<?>) {
            client.player.closeContainer();
        }
    }

    private static boolean isOnHypixel(Minecraft client) {
        if (client.getCurrentServer() == null || client.getCurrentServer().ip == null) {
            return false;
        }
        String address = client.getCurrentServer().ip.toLowerCase(Locale.ROOT).split(":", 2)[0];
        return address.equals("hypixel.net") || address.endsWith(".hypixel.net");
    }

    private static boolean isInSkyBlock(Minecraft client) {
        String title = sidebarTitle(client);
        return title.contains("SKYBLOCK");
    }

    private static boolean isGuesting(Minecraft client) {
        String title = sidebarTitle(client);
        return title.contains("SKYBLOCK") && title.contains("GUEST");
    }

    private static String sidebarTitle(Minecraft client) {
        if (client.level == null) {
            return "";
        }
        Objective objective = client.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) {
            return "";
        }
        return objective.getDisplayName().getString().toUpperCase(Locale.ROOT);
    }

    private void resetState() {
        state = State.MONITORING;
        timer = 0;
        nonGuestTicks = 0;
        menuSeenTicks = 0;
        loggedVisitCandidates = false;
    }

    private static Config loadConfig() {
        Config defaults = new Config();
        try {
            if (Files.notExists(CONFIG_PATH)) {
                saveConfig(defaults);
                return defaults;
            }

            try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                Config loaded = GSON.fromJson(reader, Config.class);
                if (loaded == null) {
                    return defaults;
                }
                loaded.normalize();
                return loaded;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to read SocialXPFarm config; using defaults.", e);
            return defaults;
        }
    }

    private static void saveConfig(Config config) throws Exception {
        Files.createDirectories(CONFIG_PATH.getParent());
        try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
        }
    }

    private enum State {
        MONITORING,
        WAITING_FOR_SKYBLOCK,
        WAITING_FOR_MENU,
        WAITING_FOR_TRANSFER,
        RETRY_DELAY
    }

    private static final class Config {
        boolean enabled = true;
        String targetPlayer = "";
        String profileName = "";

        int guestExitGraceTicks = 60;
        int menuSettleTicks = 6;
        int visitMenuTimeoutTicks = 120;
        int transferTimeoutTicks = 200;
        int skyBlockJoinTimeoutTicks = 240;
        int retryDelayTicks = 100;

        void normalize() {
            if (targetPlayer == null) targetPlayer = "";
            if (profileName == null) profileName = "";
            guestExitGraceTicks = Math.max(20, guestExitGraceTicks);
            menuSettleTicks = Math.max(1, menuSettleTicks);
            visitMenuTimeoutTicks = Math.max(40, visitMenuTimeoutTicks);
            transferTimeoutTicks = Math.max(40, transferTimeoutTicks);
            skyBlockJoinTimeoutTicks = Math.max(80, skyBlockJoinTimeoutTicks);
            retryDelayTicks = Math.max(20, retryDelayTicks);
        }

        boolean isConfigured() {
            return !targetPlayer.isBlank() && !profileName.isBlank();
        }
    }
}
