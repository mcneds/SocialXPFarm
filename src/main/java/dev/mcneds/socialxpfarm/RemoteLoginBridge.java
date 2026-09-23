package dev.mcneds.socialxpfarm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.mcneds.socialxpfarm.auth.DevicePrompt;
import dev.mcneds.socialxpfarm.auth.MicrosoftAuthClient;
import dev.mcneds.socialxpfarm.auth.PhoneLoginSession;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Outbound-only local companion client. No Microsoft credentials are part of this protocol. */
final class RemoteLoginBridge {
    static final RemoteLoginBridge INSTANCE = new RemoteLoginBridge();
    private static final Gson JSON = new Gson();
    record Snapshot(String runId, String context, String username, String accountId, String state,
                    String message, boolean canLogin, DevicePrompt prompt, boolean canTest) {
        Snapshot(String runId, String context, String username, String accountId, String state,
                 String message, boolean canLogin, DevicePrompt prompt) {
            this(runId, context, username, accountId, state, message, canLogin, prompt, false);
        }
    }
    record Command(String id, String runId, String context, String action, long expiresAt) {}
    record Config(boolean enabled, String instanceId, String secret, int port, String clientId) {
        Config {
            UUID.fromString(instanceId);
            if (secret == null || !secret.matches("[A-Za-z0-9_-]{32,128}")) throw new IllegalArgumentException();
            if (port < 1024 || port > 65535) throw new IllegalArgumentException();
            if (clientId == null) clientId = MicrosoftAuthClient.CLIENT_ID;
            UUID.fromString(clientId);
        }
        @Override public String toString() { return "RemoteConfig[credentials=REDACTED]"; }
    }
    private final String runId = UUID.randomUUID().toString();
    private Config config;
    private volatile Snapshot snapshot;
    private volatile String acknowledged = "";
    private final AtomicReference<Command> commands = new AtomicReference<>();
    private String handled = "";
    private net.minecraft.client.User testUser;
    private String testContext = "";
    private boolean testAvailable;
    private PhoneLoginSession testSession;

    private RemoteLoginBridge() {}

    void initialize() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("socialxpfarm-auth/remote.json");
        if (!Files.exists(file)) return;
        try {
            config = readConfig(file);
            if (config == null || !config.enabled()) { config = null; return; }
            Thread.startVirtualThread(this::exchangeLoop);
        } catch (Exception e) {
            config = null;
            LoggerFactory.getLogger(SocialXPFarmClient.MOD_ID).warn("Remote login configuration is invalid; rerun the companion setup wizard.");
        }
    }

    boolean enabled() { return config != null; }
    String clientId() { return config.clientId(); }

    void tick(Minecraft client, boolean enabled) {
        ConnectionRecovery recovery = ConnectionRecovery.INSTANCE;
        if (testSession != null) {
            if (!enabled || client.getUser() != testSession.expected() || !connectedToHypixel(client)) cancelTest();
            else testSession.tick(true, client.getUser());
        }
        Command command = commands.getAndSet(null);
        if (command != null) {
            Snapshot current = currentSnapshot(client, enabled);
            if (!command.id().equals(handled) && permitted(current, command, System.currentTimeMillis())) {
                if (command.action().equals("test")) {
                    cancelTest();
                    testSession = new PhoneLoginSession(AutomaticLogin.create(), client.getUser(), clientId());
                }
                else if (testSession != null) testSession.command(command.context(), command.action());
                else recovery.remoteCommand(client, command.context(), command.action());
            }
            handled = command.id();
            acknowledged = command.id();
        }
        snapshot = currentSnapshot(client, enabled);
    }

    private Snapshot currentSnapshot(Minecraft client, boolean enabled) {
        if (testSession != null) {
            testAvailable = false;
            return new Snapshot(runId, testSession.context(), testSession.expected().getName(), testSession.expected().getProfileId().toString(),
                    testSession.state(), testSession.message(), testSession.canLogin(), testSession.prompt(),
                    enabled && (testSession.state().equals("paired") || testSession.state().equals("failed"))
                            && !(client.gui.screen() instanceof AutomaticLoginScreen));
        }
        Snapshot current = ConnectionRecovery.INSTANCE.remoteSnapshot(client, runId, enabled);
        boolean available = enabled && enabled() && connectedToHypixel(client)
                && !(client.gui.screen() instanceof AutomaticLoginScreen) && AutomaticLogin.available()
                && testState(current.state());
        if (available && (!testAvailable || testUser != client.getUser())) {
            testContext = UUID.randomUUID().toString();
            testUser = client.getUser();
        }
        testAvailable = available;
        return available ? new Snapshot(runId, testContext, current.username(), current.accountId(),
                current.state(), current.message(), false, null, true) : current;
    }

    static boolean testState(String state) {
        return "idle".equals(state) || "restored".equals(state) || "signed_in".equals(state) || "paired".equals(state) || "failed".equals(state);
    }

    private static boolean connectedToHypixel(Minecraft client) {
        return client.player != null && client.getConnection() != null && client.getCurrentServer() != null
                && RecoveryPolicy.isHypixel(client.getCurrentServer().ip);
    }

    void cancelTest() {
        if (testSession != null) testSession.close();
        testSession = null;
    }

    private void exchangeLoop() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        URI endpoint = URI.create("http://127.0.0.1:" + config.port() + "/v1/instances/" + config.instanceId() + "/exchange");
        Snapshot previous = null;
        long sentAt = 0;
        boolean warned = false;
        while (true) {
            try {
                Snapshot current = snapshot;
                if (current != null) {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("runId", runId);
                    payload.addProperty("ack", acknowledged);
                    long now = System.nanoTime();
                    if (!current.equals(previous) || now - sentAt >= 15_000_000_000L)
                        payload.add("snapshot", JSON.toJsonTree(current));
                    HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                            .header("Authorization", "Bearer " + config.secret()).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
                    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) throw new IllegalStateException();
                    JsonObject result = JSON.fromJson(response.body(), JsonObject.class);
                    if (result.has("resync") && result.get("resync").getAsBoolean()) previous = null;
                    else if (payload.has("snapshot")) { previous = current; sentAt = now; }
                    if (result.has("command") && !result.get("command").isJsonNull()) {
                        Command command = JSON.fromJson(result.get("command"), Command.class);
                        if (valid(command) && !command.id().equals(acknowledged)) commands.compareAndSet(null, command);
                    }
                    warned = false;
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            catch (Exception e) {
                previous = null;
                if (!warned) LoggerFactory.getLogger(SocialXPFarmClient.MOD_ID)
                        .warn("Remote login companion unavailable; local recovery remains active.");
                warned = true;
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    static boolean valid(Command command) {
        if (command == null || command.id() == null || command.runId() == null || command.context() == null) return false;
        try {
            UUID.fromString(command.id()); UUID.fromString(command.runId()); UUID.fromString(command.context());
            return "login".equals(command.action()) || "cancel".equals(command.action()) || "test".equals(command.action());
        } catch (IllegalArgumentException e) { return false; }
    }

    static Config readConfig(Path file) throws java.io.IOException {
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(file.getParent())) throw new java.io.IOException("Invalid remote storage");
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            for (Path path : new Path[]{file, file.getParent()}) {
                if (Files.getPosixFilePermissions(path).stream().anyMatch(p -> !p.name().startsWith("OWNER_")))
                    throw new java.io.IOException("Remote storage must be owner-only");
            }
        }
        return JSON.fromJson(Files.readString(file), Config.class);
    }

    static boolean permitted(Snapshot state, Command command, long now) {
        if (!valid(command) || !state.runId().equals(command.runId()) || !state.context().equals(command.context())
                || command.expiresAt() <= now || command.expiresAt() - now > 65_000) return false;
        if (command.action().equals("test")) return state.canTest() && testState(state.state());
        return command.action().equals("login")
                ? state.canLogin() && (state.state().equals("needs_login") || state.state().equals("cancelled"))
                : state.state().equals("signing_in");
    }
}
