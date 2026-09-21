package dev.mcneds.socialxpfarm;

import dev.mcneds.socialxpfarm.mixin.DisconnectedScreenAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerReconfigScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Keeps connection recovery separate from the island/menu state machine. */
public final class ConnectionRecovery {
    public static final ConnectionRecovery INSTANCE = new ConnectionRecovery();
    private static final Logger LOGGER = LoggerFactory.getLogger(SocialXPFarmClient.MOD_ID);

    private ServerData server;
    private DisconnectedScreen failureScreen;
    private User staleUser;
    private int attempts;
    private final RecoveryDeadline retry = new RecoveryDeadline();
    private final RecoveryDeadline loading = new RecoveryDeadline();
    private Screen loadingScreen;
    private boolean paused;
    private int protocolFailures;
    private int healthyTicks;

    private ConnectionRecovery() {}

    // Called before any connection attempt, including ones that fail before entering a world.
    public void connecting(ServerData destination) {
        if (!RecoveryPolicy.isHypixel(destination.ip)) {
            reset();
            return;
        }
        server = destination;
        ServerSignals.INSTANCE.reset();
    }

    void tick(Minecraft client, boolean enabled, int initialDelay, int maximumDelay, int connectTimeoutTicks) {
        if (!enabled) {
            reset();
            return;
        }
        Screen screen = client.gui.screen();
        if (server != null && isLoading(screen)) {
            if (screen != loadingScreen) {
                loadingScreen = screen;
                loading.startTicks(connectTimeoutTicks);
            } else if (loading.expired()) {
                LOGGER.warn("Connection/loading stage stalled; aborting it before retrying.");
                if (screen instanceof ConnectionAttemptControl control) control.socialxpfarm$abort();
                forceReconnect(client, "Connection or world loading timed out");
            }
            return;
        }
        loadingScreen = null;
        loading.clear();
        if (client.player != null && client.getConnection() != null) {
            ServerData current = client.getCurrentServer();
            if (current == null || !RecoveryPolicy.isHypixel(current.ip)) {
                reset();
            } else {
                server = current;
                failureScreen = null;
                staleUser = null;
            }
            return;
        }

        if (screen instanceof TitleScreen || screen instanceof JoinMultiplayerScreen) {
            reset(); // Manual disconnect, cancelled connection, or leaving the failure screen.
            return;
        }
        if (server == null || !(screen instanceof DisconnectedScreen disconnected)) {
            return; // Never replace a connection, world-loading, or authentication screen.
        }

        if (failureScreen != disconnected) {
            failureScreen = disconnected;
            healthyTicks = 0;
            Component reason = ((DisconnectedScreenAccessor) disconnected).socialxpfarm$getDetails().reason();
            RecoveryPolicy.Action action = RecoveryPolicy.classify(reason);
            LOGGER.warn("Disconnect classified as {}: {}", action, reason.getString());
            paused = action == RecoveryPolicy.Action.MANUAL
                    || (action == RecoveryPolicy.Action.PROTOCOL && ++protocolFailures > 3);
            if (paused) {
                LOGGER.warn("Automatic recovery paused. Resolve the account/client/server error shown on screen, then reconnect manually.");
                return;
            }
            if (action == RecoveryPolicy.Action.AUTHENTICATE) {
                staleUser = client.getUser();
                LOGGER.warn("Session rejected; waiting for reauthentication before reconnecting.");
                openAuthMe(client, disconnected);
                return;
            }
            staleUser = null;
            int minimum = action == RecoveryPolicy.Action.COOLDOWN ? Math.max(1200, initialDelay) : initialDelay;
            schedule(minimum, Math.max(minimum, maximumDelay));
        }

        if (paused) return;

        if (staleUser != null) {
            User current = client.getUser();
            if (current == staleUser || current.getAccessToken().equals(staleUser.getAccessToken())
                    || current.getAccessToken().isBlank() || current.getAccessToken().equals("invalidtoken")) {
                return; // Cancellation or an offline login must not start another retry loop.
            }
            staleUser = null;
            attempts = 0;
            schedule(initialDelay, maximumDelay);
            LOGGER.info("Session changed; resuming reconnect recovery.");
        }

        if (retry.expired()) {
            retry.clear();
            LOGGER.info("Reconnecting to {} (attempt {}).", server.ip, attempts);
            ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), client,
                    ServerAddress.parseString(server.ip), server, false, null);
        }
    }

    private void schedule(int initialDelay, int maximumDelay) {
        int delay = RecoveryPolicy.reconnectDelay(initialDelay, maximumDelay, attempts);
        int jitter = ThreadLocalRandom.current().nextInt(Math.max(1, delay / 5));
        delay = (int) Math.min(maximumDelay, (long) delay + jitter);
        retry.startTicks(delay);
        attempts = Math.min(attempts + 1, 31);
        LOGGER.info("Disconnected; reconnecting in approximately {} seconds.", delay / 20);
    }

    static boolean isSessionFailure(Component reason) {
        return RecoveryPolicy.classify(reason) == RecoveryPolicy.Action.AUTHENTICATE;
    }

    private static void openAuthMe(Minecraft client, Screen parent) {
        if (!FabricLoader.getInstance().isModLoaded("authme")) {
            LOGGER.warn("Install Auth Me for in-game reauthentication, or restart Minecraft to renew the session.");
            return;
        }
        try {
            // Auth Me's public screen API; keep it optional so recovery works without the mod.
            // Returning to this same failure screen allows us to distinguish success from cancellation.
            Class<? extends Screen> type = Class.forName("me.axieum.mcmod.authme.api.gui.screen.AuthMethodScreen")
                    .asSubclass(Screen.class);
            client.setScreenAndShow(type.getConstructor(Screen.class).newInstance(parent));
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            LOGGER.warn("Could not open Auth Me; use its Re-Login button to renew the session.", e);
        }
    }

    void markHealthy() {
        if (healthyTicks < 600 && ++healthyTicks == 600) {
            attempts = 0;
            protocolFailures = 0;
        }
    }

    void markUnhealthy() { healthyTicks = 0; }

    static boolean isLoading(Screen screen) {
        return screen instanceof ConnectScreen || screen instanceof LevelLoadingScreen || screen instanceof ServerReconfigScreen;
    }

    void forceReconnect(Minecraft client, String reason) {
        if (server == null) return;
        Screen parent = new JoinMultiplayerScreen(new TitleScreen());
        client.disconnect(new DisconnectedScreen(parent, Component.literal("Connection recovery"),
                Component.literal(reason)), false);
    }

    void reset() {
        server = null;
        failureScreen = null;
        staleUser = null;
        attempts = 0;
        retry.clear();
        loading.clear();
        loadingScreen = null;
        paused = false;
        protocolFailures = 0;
        healthyTicks = 0;
        ServerSignals.INSTANCE.reset();
    }
}
