package dev.mcneds.socialxpfarm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
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

    private Config config;
    private State state = State.MONITORING;
    private int timer;
    private int nonGuestTicks;
    private int menuSeenTicks;
    private boolean warnedUnconfigured;

    @Override
    public void onInitializeClient() {
        config = loadConfig();
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        LOGGER.info("SocialXPFarm loaded. Config: {}", CONFIG_PATH.toAbsolutePath());
    }

    private void tick(MinecraftClient client) {
        if (config == null || !config.enabled || !config.isConfigured()) {
            if (!warnedUnconfigured && client.player != null) {
                warnedUnconfigured = true;
                LOGGER.warn("SocialXPFarm is idle until targetPlayer and profileName are set in {}", CONFIG_PATH.toAbsolutePath());
            }
            return;
        }

        if (!isOnHypixel(client) || client.player == null || client.getNetworkHandler() == null) {
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

    private void recover(MinecraftClient client) {
        nonGuestTicks = 0;
        if (isInSkyBlock(client)) {
            sendVisit(client);
        } else {
            sendPlaySkyBlock(client);
        }
    }

    private void sendPlaySkyBlock(MinecraftClient client) {
        closeHandledScreen(client);
        LOGGER.info("Not in SkyBlock; sending /play sb before retrying visit.");
        client.getNetworkHandler().sendChatCommand("play sb");
        state = State.WAITING_FOR_SKYBLOCK;
        timer = config.skyBlockJoinTimeoutTicks;
    }

    private void sendVisit(MinecraftClient client) {
        closeHandledScreen(client);
        LOGGER.info("Sending /visit {}", config.targetPlayer);
        client.getNetworkHandler().sendChatCommand("visit " + config.targetPlayer);
        state = State.WAITING_FOR_MENU;
        timer = config.visitMenuTimeoutTicks;
        menuSeenTicks = 0;
    }

    private boolean tryClickConfiguredProfile(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> handled) || client.interactionManager == null || client.player == null) {
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

        ScreenHandler handler = handled.getScreenHandler();
        String wantedProfile = config.profileName.toLowerCase(Locale.ROOT);

        for (Slot slot : handler.slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) {
                continue;
            }

            String itemName = stack.getName().getString().trim().toLowerCase(Locale.ROOT);
            if (itemName.equals(wantedProfile) || itemName.contains(wantedProfile)) {
                LOGGER.info("Clicking SkyBlock profile '{}' in visit menu (slot {}).", config.profileName, slot.id);
                client.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, client.player);
                return true;
            }
        }

        return false;
    }

    private void scheduleRetry(MinecraftClient client, String reason) {
        LOGGER.warn("Recovery attempt failed: {}. Retrying shortly.", reason);
        closeHandledScreen(client);
        state = State.RETRY_DELAY;
        timer = config.retryDelayTicks;
        menuSeenTicks = 0;
    }

    private static void closeHandledScreen(MinecraftClient client) {
        if (client.player != null && client.currentScreen instanceof HandledScreen<?>) {
            client.player.closeHandledScreen();
        }
    }

    private static boolean isOnHypixel(MinecraftClient client) {
        if (client.getCurrentServerEntry() == null || client.getCurrentServerEntry().address == null) {
            return false;
        }
        String address = client.getCurrentServerEntry().address.toLowerCase(Locale.ROOT);
        return address.equals("hypixel.net") || address.endsWith(".hypixel.net") || address.contains("hypixel.net:");
    }

    private static boolean isInSkyBlock(MinecraftClient client) {
        String title = sidebarTitle(client);
        return title.contains("SKYBLOCK");
    }

    private static boolean isGuesting(MinecraftClient client) {
        String title = sidebarTitle(client);
        return title.contains("SKYBLOCK") && title.contains("GUEST");
    }

    private static String sidebarTitle(MinecraftClient client) {
        if (client.world == null) {
            return "";
        }
        ScoreboardObjective objective = client.world.getScoreboard().getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
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
