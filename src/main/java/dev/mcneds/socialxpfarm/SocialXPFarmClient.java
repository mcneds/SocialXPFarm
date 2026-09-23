package dev.mcneds.socialxpfarm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
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
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

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
    private final ConnectionRecovery connectionRecovery = ConnectionRecovery.INSTANCE;
    private final RecoveryDeadline limboRecovery = new RecoveryDeadline();
    private int recoveryFailures;
    private int healthyTicks;
    private KeyMapping toggleKey;

    @Override
    public void onInitializeClient() {
        config = loadConfig();
        RemoteLoginBridge.INSTANCE.initialize();
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.socialxpfarm.toggle",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "controls"))));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
                literal("sxp").requires(FabricClientCommandSource::attended)
                        .executes(command -> showStatus(command.getSource().getClient()))
                        .then(literal("toggle").executes(command -> setEnabled(command.getSource().getClient(), !config.enabled)))
                        .then(literal("on").executes(command -> setEnabled(command.getSource().getClient(), true)))
                        .then(literal("off").executes(command -> setEnabled(command.getSource().getClient(), false)))
                        .then(literal("auth")
                                .executes(command -> AutomaticLogin.status(command.getSource().getClient()))
                                .then(literal("login").executes(command -> AutomaticLogin.setup(command.getSource().getClient())))
                                .then(literal("forget").executes(command -> AutomaticLogin.forget(command.getSource().getClient()))))
                        .then(literal("mode")
                                .then(literal("own").executes(command -> setDestination(command.getSource().getClient(), IslandDestination.OWN)))
                                .then(literal("guest").executes(command -> setDestination(command.getSource().getClient(), IslandDestination.GUEST))))));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> ServerSignals.INSTANCE.accept(message.getString()));
        LOGGER.info("SocialXPFarm {} loaded (enabled={}, destination={}). Config: {}",
                FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow().getMetadata().getVersion().getFriendlyString(),
                config.enabled, config.destination, CONFIG_PATH.toAbsolutePath());
    }

    private void tick(Minecraft client) {
        tickAutomation(client);
        RemoteLoginBridge.INSTANCE.tick(client, config != null && config.shouldRun() && config.autoReconnect);
    }

    private void tickAutomation(Minecraft client) {
        while (toggleKey.consumeClick()) setEnabled(client, !config.enabled);
        if (config == null || !config.shouldRun()) {
            connectionRecovery.reset(false);
            resetState();
            if (config != null && config.enabled && !warnedUnconfigured && client.player != null) {
                warnedUnconfigured = true;
                LOGGER.warn("SocialXPFarm is idle until targetPlayer and profileName are set in {}", CONFIG_PATH.toAbsolutePath());
            }
            return;
        }

        connectionRecovery.tick(client, config.enabled && config.autoReconnect, config.reconnectDelayTicks,
                config.maxReconnectDelayTicks, config.connectTimeoutTicks);

        if (client.gui.screen() instanceof AutomaticLoginScreen) {
            resetState();
            limboRecovery.clear();
            return;
        }

        if (ConnectionRecovery.isLoading(client.gui.screen())) {
            resetState();
            connectionRecovery.markUnhealthy();
            return;
        }

        if (!isOnHypixel(client) || client.player == null || client.getConnection() == null) {
            resetState();
            return;
        }

        if (destination().reached(sidebarTitle(client), sidebarLines(client))) {
            ServerSignals.INSTANCE.clearQueue();
            limboRecovery.clear();
            if (healthyTicks < 600 && ++healthyTicks == 600) recoveryFailures = 0;
            connectionRecovery.markHealthy();
            if (state != State.MONITORING) {
                LOGGER.info("Destination restored ({}); recovery complete.", destination().label);
            }
            state = State.MONITORING;
            timer = 0;
            nonGuestTicks = 0;
            menuSeenTicks = 0;
            loggedVisitCandidates = false;
            return;
        }

        healthyTicks = 0;
        connectionRecovery.markUnhealthy();
        if (isInSkyBlock(client)) ServerSignals.INSTANCE.clearQueue();
        if (ServerSignals.INSTANCE.queued() || sidebarTitle(client).contains("QUEUE") || ServerSignals.INSTANCE.throttled()) {
            limboRecovery.clear();
            return;
        }
        boolean possibleLimbo = sidebarTitle(client).isBlank() || sidebarTitle(client).contains("LIMBO");
        if (!possibleLimbo) {
            limboRecovery.clear();
        } else if (config.enabled && config.autoReconnect && !limboRecovery.active() && !limboRecovery.expired()) {
            limboRecovery.startTicks(config.limboReconnectTicks);
        }
        if (possibleLimbo && limboRecovery.expired() && config.enabled && config.autoReconnect) {
            connectionRecovery.forceReconnect(client, "Limbo recovery commands did not restore a lobby");
            resetState();
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
                    sendDestination(client);
                } else if (--timer <= 0) {
                    scheduleRetry(client, "SkyBlock join did not complete");
                }
            }
            case WAITING_FOR_LOBBY -> {
                if (isInSkyBlock(client)) {
                    sendDestination(client);
                } else if (!sidebarTitle(client).isBlank() && !sidebarTitle(client).contains("LIMBO")) {
                    sendPlaySkyBlock(client);
                } else if (--timer <= 0) {
                    // Try SkyBlock even if the lobby did not publish a sidebar.
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
            sendDestination(client);
        } else if (sidebarTitle(client).isBlank() || sidebarTitle(client).contains("LIMBO")) {
            closeHandledScreen(client);
            LOGGER.info("Possible limbo; sending /lobby before joining SkyBlock.");
            client.getConnection().sendCommand("lobby");
            state = State.WAITING_FOR_LOBBY;
            timer = config.lobbyJoinTimeoutTicks;
        } else {
            sendPlaySkyBlock(client);
        }
    }

    private void sendPlaySkyBlock(Minecraft client) {
        closeHandledScreen(client);
        LOGGER.info("Not in SkyBlock; sending /play sb before returning to {}.", destination().label);
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

    private IslandDestination destination() {
        return config.destination;
    }

    private void sendDestination(Minecraft client) {
        if (destination() == IslandDestination.GUEST) {
            sendVisit(client);
            return;
        }
        closeHandledScreen(client);
        LOGGER.info("Sending /is to return to own island.");
        client.getConnection().sendCommand("is");
        state = State.WAITING_FOR_TRANSFER;
        timer = config.transferTimeoutTicks;
    }

    private int showStatus(Minecraft client) {
        feedback(client, "Farming " + (config.enabled ? "ON" : "OFF") + "; destination: " + config.destination.label
                + ". /sxp toggle | on | off | mode own | mode guest");
        return 1;
    }

    private int setEnabled(Minecraft client, boolean enabled) {
        if (enabled && !config.isConfigured()) {
            feedback(client, "Set targetPlayer and profileName for guest mode, or use /sxp mode own first.");
            return 0;
        }
        config.enabled = enabled;
        controlsChanged(client);
        feedback(client, enabled ? "Automation ON; destination: " + config.destination.label + "."
                : "Automation OFF. All recovery actions and reconnects are disabled.");
        return 1;
    }

    private int setDestination(Minecraft client, IslandDestination destination) {
        if (!destination.isConfigured(config.targetPlayer, config.profileName)) {
            feedback(client, "Guest mode needs targetPlayer and profileName in the config.");
            return 0;
        }
        config.destination = destination;
        controlsChanged(client);
        feedback(client, "Destination: " + destination.label + ". Farming " + (config.enabled ? "ON." : "OFF; use /sxp on to start."));
        return 1;
    }

    private void controlsChanged(Minecraft client) {
        resetState();
        connectionRecovery.reset(false);
        recoveryFailures = 0;
        warnedUnconfigured = false;
        try {
            saveConfig(config);
        } catch (Exception e) {
            LOGGER.error("Could not save SocialXPFarm controls.", e);
            feedback(client, "Settings changed for this session, but could not be saved. Check the log.");
        }
    }

    private static void feedback(Minecraft client, String message) {
        LOGGER.info(message);
        if (client.player != null) client.player.sendSystemMessage(Component.literal("[SocialXPFarm] " + message));
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
        timer = RecoveryPolicy.reconnectDelay(config.retryDelayTicks, config.maxRetryDelayTicks, recoveryFailures);
        recoveryFailures = Math.min(31, recoveryFailures + 1);
        menuSeenTicks = 0;
        loggedVisitCandidates = false;
    }

    private static void closeHandledScreen(Minecraft client) {
        if (client.player != null && client.gui.screen() instanceof AbstractContainerScreen<?>) {
            client.player.closeContainer();
        }
    }

    private static boolean isOnHypixel(Minecraft client) {
        return client.getCurrentServer() != null && RecoveryPolicy.isHypixel(client.getCurrentServer().ip);
    }

    private static boolean isInSkyBlock(Minecraft client) {
        String title = sidebarTitle(client);
        return title.contains("SKYBLOCK");
    }

    private static List<String> sidebarLines(Minecraft client) {
        if (client.level == null) return List.of();
        var scoreboard = client.level.getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) return List.of();
        return scoreboard.listPlayerScores(objective).stream().filter(score -> !score.isHidden())
                .map(score -> PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(score.owner()), score.ownerName()).getString())
                .toList();
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
        limboRecovery.clear();
        healthyTicks = 0;
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
        WAITING_FOR_LOBBY,
        WAITING_FOR_SKYBLOCK,
        WAITING_FOR_MENU,
        WAITING_FOR_TRANSFER,
        RETRY_DELAY
    }

    static final class Config {
        boolean enabled = true;
        IslandDestination destination = IslandDestination.GUEST;
        String targetPlayer = "";
        String profileName = "";

        int guestExitGraceTicks = 60;
        int menuSettleTicks = 6;
        int visitMenuTimeoutTicks = 120;
        int transferTimeoutTicks = 200;
        int skyBlockJoinTimeoutTicks = 240;
        int retryDelayTicks = 100;
        int lobbyJoinTimeoutTicks = 100;
        boolean autoReconnect = true;
        int reconnectDelayTicks = 200;
        int maxReconnectDelayTicks = 1200;
        int connectTimeoutTicks = 2400;
        int limboReconnectTicks = 2400;
        int maxRetryDelayTicks = 1200;

        void normalize() {
            if (destination == null) destination = IslandDestination.GUEST;
            if (targetPlayer == null) targetPlayer = "";
            if (profileName == null) profileName = "";
            guestExitGraceTicks = Math.max(20, guestExitGraceTicks);
            menuSettleTicks = Math.max(1, menuSettleTicks);
            visitMenuTimeoutTicks = Math.max(40, visitMenuTimeoutTicks);
            transferTimeoutTicks = Math.max(40, transferTimeoutTicks);
            skyBlockJoinTimeoutTicks = Math.max(80, skyBlockJoinTimeoutTicks);
            retryDelayTicks = Math.max(20, retryDelayTicks);
            lobbyJoinTimeoutTicks = Math.max(40, lobbyJoinTimeoutTicks);
            reconnectDelayTicks = Math.max(100, reconnectDelayTicks);
            maxReconnectDelayTicks = Math.max(reconnectDelayTicks, maxReconnectDelayTicks);
            connectTimeoutTicks = Math.max(600, connectTimeoutTicks);
            limboReconnectTicks = Math.max(600, limboReconnectTicks);
            maxRetryDelayTicks = Math.max(retryDelayTicks, maxRetryDelayTicks);
        }

        boolean isConfigured() {
            return destination.isConfigured(targetPlayer, profileName);
        }

        boolean shouldRun() {
            return enabled && isConfigured();
        }
    }
}
