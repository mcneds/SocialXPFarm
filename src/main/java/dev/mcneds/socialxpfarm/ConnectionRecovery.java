package dev.mcneds.socialxpfarm;

import dev.mcneds.socialxpfarm.mixin.DisconnectedScreenAccessor;
import dev.mcneds.socialxpfarm.auth.SessionRefresh;
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
    private final AuthenticationRecovery<Screen> authentication = new AuthenticationRecovery<>();
    private SessionRefresh automaticLogin;
    private User rejectedUser;
    private boolean loginScreenShown;
    private SessionRefresh.Status lastLoginStatus;
    private String remoteContext = "";
    private String remotePhase = "idle";
    private String remoteMessage = "";
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
            reset(false); // In-server recovery can still need queue/throttle signals when reconnecting is disabled.
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
                authentication.clear();
                cancelAutomaticLogin();
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
                retry.clear();
                LOGGER.warn("Session rejected; waiting for reauthentication before reconnecting.");
                authentication.begin(disconnected, client.getUser(), () -> beginLogin(client, disconnected));
                return;
            }
            authentication.clear();
            cancelAutomaticLogin();
            int minimum = action == RecoveryPolicy.Action.COOLDOWN ? Math.max(1200, initialDelay) : initialDelay;
            schedule(minimum, Math.max(minimum, maximumDelay));
        }

        if (paused) return;

        tickLogin(client, disconnected);
        if (paused || client.gui.screen() != disconnected) return;

        AuthenticationRecovery.Result auth = authentication.poll(screen, client.getUser());
        if (auth == AuthenticationRecovery.Result.WRONG_ACCOUNT) {
            LOGGER.warn("Auth Me signed into a different Minecraft account. Use Re-Login to sign back into {}; automatic reconnect remains paused.",
                    authentication.expectedName());
            return;
        }
        if (auth == AuthenticationRecovery.Result.WAITING) return;
        if (auth == AuthenticationRecovery.Result.READY) {
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

    private void beginLogin(Minecraft client, DisconnectedScreen parent) {
        cancelAutomaticLogin();
        if (!AutomaticLogin.available()) {
            remoteContext = java.util.UUID.randomUUID().toString();
            remotePhase = "failed";
            remoteMessage = "Install the supported Auth Me version for session renewal.";
            openAuthMe(client, parent);
            return;
        }
        rejectedUser = client.getUser();
        remoteContext = java.util.UUID.randomUUID().toString();
        remotePhase = "renewing";
        automaticLogin = AutomaticLogin.create();
        automaticLogin.start(rejectedUser);
        tickLogin(client, parent);
    }

    private void tickLogin(Minecraft client, DisconnectedScreen parent) {
        if (automaticLogin == null) return;
        // A manual Auth Me login takes precedence over an older background operation.
        if (client.getUser() != rejectedUser) {
            User current = client.getUser();
            boolean renewed = current.getProfileId().equals(rejectedUser.getProfileId())
                    && !current.getAccessToken().equals(rejectedUser.getAccessToken())
                    && !current.getAccessToken().isBlank() && !current.getAccessToken().equals("invalidtoken");
            remotePhase = renewed ? "signed_in" : "cancelled";
            remoteMessage = "Session changed manually.";
            cancelAutomaticLogin();
            return;
        }
        automaticLogin.tick();
        SessionRefresh.Status status = automaticLogin.status();
        if (status != lastLoginStatus) {
            lastLoginStatus = status;
            LOGGER.info("Automatic login: {}", automaticLogin.message());
        }
        if (status == SessionRefresh.Status.READY) {
            if (!AutomaticLogin.apply(automaticLogin.result())) {
                paused = true;
                LOGGER.warn("Could not install the renewed session. Check the Auth Me version and restart this instance.");
            }
            remotePhase = paused ? "failed" : "signed_in";
            remoteMessage = paused ? "Session installation failed; check Auth Me." : "Signed in; reconnecting.";
            cancelAutomaticLogin();
        } else if ((status == SessionRefresh.Status.NEEDS_LOGIN || status == SessionRefresh.Status.FAILED)
                && !loginScreenShown && !RemoteLoginBridge.INSTANCE.enabled()) {
            loginScreenShown = true;
            client.setScreenAndShow(new AutomaticLoginScreen(parent, automaticLogin, rejectedUser,
                    status == SessionRefresh.Status.NEEDS_LOGIN));
        }
    }

    void remoteCommand(Minecraft client, String context, String action) {
        if (!RemoteLoginBridge.INSTANCE.enabled() || !remoteContext.equals(context) || automaticLogin == null
                || paused || client.getUser() != rejectedUser || client.gui.screen() != failureScreen) return;
        SessionRefresh.Status status = automaticLogin.status();
        if (action.equals("login") && (status == SessionRefresh.Status.NEEDS_LOGIN || status == SessionRefresh.Status.IDLE)) {
            remoteContext = java.util.UUID.randomUUID().toString();
            remotePhase = "signing_in";
            automaticLogin.pairDevice(rejectedUser, RemoteLoginBridge.INSTANCE.clientId());
        } else if (action.equals("cancel") && remotePhase.equals("signing_in")) {
            automaticLogin.cancel();
            remoteContext = java.util.UUID.randomUUID().toString();
            remotePhase = "cancelled";
            remoteMessage = "Phone sign-in cancelled.";
        }
    }

    RemoteLoginBridge.Snapshot remoteSnapshot(Minecraft client, String runId, boolean enabled) {
        String phase = enabled ? remotePhase : "disabled";
        String message = remoteMessage;
        boolean canLogin = false;
        dev.mcneds.socialxpfarm.auth.DevicePrompt prompt = null;
        if (enabled && automaticLogin != null) {
            SessionRefresh.Status status = automaticLogin.status();
            phase = switch (status) {
                case NEEDS_LOGIN -> "needs_login";
                case FAILED -> "failed";
                case IDLE -> "cancelled";
                case RUNNING -> remotePhase.equals("signing_in") ? "signing_in" : "renewing";
                case RETRY_WAIT -> "renewing";
                case READY -> "signing_in";
            };
            message = automaticLogin.message();
            canLogin = (status == SessionRefresh.Status.NEEDS_LOGIN || status == SessionRefresh.Status.IDLE)
                    && !paused && client.gui.screen() == failureScreen && client.getUser() == rejectedUser;
            prompt = automaticLogin.devicePrompt();
        }
        User user = rejectedUser == null ? client.getUser() : rejectedUser;
        return new RemoteLoginBridge.Snapshot(runId, remoteContext, user.getName(), user.getProfileId().toString(),
                phase, message, canLogin, prompt);
    }

    private void cancelAutomaticLogin() {
        if (automaticLogin != null) {
            automaticLogin.cancel();
            if (remotePhase.equals("renewing") || remotePhase.equals("signing_in")) {
                remotePhase = "cancelled";
                remoteMessage = "Authentication recovery cancelled.";
            }
        }
        automaticLogin = null;
        rejectedUser = null;
        loginScreenShown = false;
        lastLoginStatus = null;
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
        if (remotePhase.equals("signed_in")) { remotePhase = "restored"; remoteMessage = "Destination restored."; }
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
        reset(true);
    }

    void reset(boolean clearSignals) {
        server = null;
        failureScreen = null;
        authentication.clear();
        cancelAutomaticLogin();
        remoteContext = "";
        remotePhase = "idle";
        remoteMessage = "";
        attempts = 0;
        retry.clear();
        loading.clear();
        loadingScreen = null;
        paused = false;
        protocolFailures = 0;
        healthyTicks = 0;
        if (clearSignals) ServerSignals.INSTANCE.reset();
    }
}
