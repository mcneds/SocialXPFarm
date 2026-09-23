package dev.mcneds.socialxpfarm;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.UUID;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RemoteLoginBridgeTest {
    @TempDir Path temp;
    private final String run = UUID.randomUUID().toString();
    private final String context = UUID.randomUUID().toString();
    private RemoteLoginBridge.Command command(String action) {
        return new RemoteLoginBridge.Command(UUID.randomUUID().toString(), run, context, action, 61_000);
    }
    private RemoteLoginBridge.Snapshot state(String phase, boolean canLogin) {
        return new RemoteLoginBridge.Snapshot(run, context, "Alt", UUID.randomUUID().toString(), phase, "", canLogin, null);
    }

    @Test void onlyCurrentPendingInstanceMayStartPhoneLogin() {
        assertTrue(RemoteLoginBridge.permitted(state("needs_login", true), command("login"), 1000));
        assertTrue(RemoteLoginBridge.permitted(state("cancelled", true), command("login"), 1000));
        assertFalse(RemoteLoginBridge.permitted(state("needs_login", false), command("login"), 1000));
    }

    @ParameterizedTest @ValueSource(strings = {"disabled", "idle", "renewing", "restored", "failed", "signed_in", "signing_in", "paired"})
    void otherStatesCannotStartAnotherGrantEvenWithOldEnabledFlag(String phase) {
        assertFalse(RemoteLoginBridge.permitted(state(phase, true), command("login"), 1000));
    }

    @Test void cancellationAppliesOnlyToPhoneSignIn() {
        assertTrue(RemoteLoginBridge.permitted(state("signing_in", false), command("cancel"), 1000));
        assertFalse(RemoteLoginBridge.permitted(state("renewing", false), command("cancel"), 1000));
        assertFalse(RemoteLoginBridge.permitted(state("disabled", false), command("cancel"), 1000));
    }

    @Test void remoteTestRequiresExplicitCapabilityAndSafeLifecycle() {
        for (String phase : new String[]{"idle", "restored", "signed_in", "paired", "disabled", "needs_login", "signing_in", "failed", "cancelled"}) {
            var base = state(phase, false);
            assertFalse(RemoteLoginBridge.permitted(base, command("test"), 1000));
            var capable = new RemoteLoginBridge.Snapshot(run, context, "Alt", base.accountId(), phase, "", false, null, true);
            assertEquals(java.util.Set.of("idle", "restored", "signed_in", "paired", "failed").contains(phase),
                    RemoteLoginBridge.permitted(capable, command("test"), 1000));
            assertFalse(RemoteLoginBridge.permitted(capable,
                    new RemoteLoginBridge.Command(UUID.randomUUID().toString(), run, UUID.randomUUID().toString(), "test", 61000), 1000));
        }
    }

    @Test void staleProcessContextExpiryAndUnknownActionAreRejected() {
        var active = state("needs_login", true);
        for (var stale : new RemoteLoginBridge.Command[]{
                new RemoteLoginBridge.Command(UUID.randomUUID().toString(), UUID.randomUUID().toString(), context, "login", 61000),
                new RemoteLoginBridge.Command(UUID.randomUUID().toString(), run, UUID.randomUUID().toString(), "login", 61000),
                new RemoteLoginBridge.Command(UUID.randomUUID().toString(), run, context, "login", 1000),
                new RemoteLoginBridge.Command(UUID.randomUUID().toString(), run, context, "login", Long.MAX_VALUE),
                command("enable"), command("execute"), new RemoteLoginBridge.Command(null, run, context, "login", 61000)}) {
            assertFalse(RemoteLoginBridge.permitted(active, stale, 1000));
        }
    }

    @Test void configurationAndWireSnapshotsDoNotExposeCredentials() {
        var config = new RemoteLoginBridge.Config(true, UUID.randomUUID().toString(), "a".repeat(43), 38471, null);
        assertFalse(config.toString().contains("a".repeat(43)));
        String json = new Gson().toJson(state("needs_login", true));
        assertFalse(json.contains("secret"));
        assertFalse(json.contains("refreshToken"));
        assertFalse(json.contains("deviceCode"));
        assertThrows(IllegalArgumentException.class, () -> new RemoteLoginBridge.Config(true, run, "short", 38471, null));
        assertThrows(IllegalArgumentException.class, () -> new RemoteLoginBridge.Config(true, run, "a".repeat(43), 80, null));
    }

    @Test void sharedFixtureMatchesCompanionProtocol() throws Exception {
        String json = Files.readString(Path.of(System.getProperty("sxp.projectDir"), "remote-login/tests/fixtures/snapshot.json"));
        Gson gson = new Gson();
        var snapshot = gson.fromJson(json, RemoteLoginBridge.Snapshot.class);
        assertEquals(com.google.gson.JsonParser.parseString(json), gson.toJsonTree(snapshot));
        assertEquals("TEST-CODE", snapshot.prompt().userCode());
        assertFalse(snapshot.canLogin());
    }

    @Test void readableByOtherUsersOrSymlinkedRemoteConfigurationIsRejected() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(temp).supportsFileAttributeView("posix"));
        Path file = temp.resolve("remote.json");
        var config = new RemoteLoginBridge.Config(true, run, "a".repeat(43), 38471, null);
        Files.writeString(file, new Gson().toJson(config));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rwx------"));
        assertEquals(config, RemoteLoginBridge.readConfig(file));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(java.io.IOException.class, () -> RemoteLoginBridge.readConfig(file));
        Path link = temp.resolve("link.json");
        Files.createSymbolicLink(link, file);
        assertThrows(java.io.IOException.class, () -> RemoteLoginBridge.readConfig(link));
    }
    @Test void browserFixtureAndCallbackCommandsAreBoundToCurrentLiveRequest() throws Exception {
        String json = Files.readString(Path.of(System.getProperty("sxp.projectDir"), "remote-login/tests/fixtures/browser-snapshot.json"));
        Gson gson = new Gson();
        var active = gson.fromJson(json, RemoteLoginBridge.Snapshot.class);
        assertEquals(com.google.gson.JsonParser.parseString(json), gson.toJsonTree(active));
        String callback = "http://localhost:43871/callback?code=synthetic-secret&state=" + "S".repeat(43);
        var command = new RemoteLoginBridge.Command(UUID.randomUUID().toString(), active.runId(), active.context(), "callback", 61000, callback);
        assertTrue(RemoteLoginBridge.permitted(active, command, 1000));
        assertFalse(command.toString().contains("synthetic-secret"));
        assertFalse(RemoteLoginBridge.permitted(active, command, 61000));
        assertFalse(RemoteLoginBridge.permitted(state("signing_in", false), command, 1000));
        assertFalse(RemoteLoginBridge.valid(new RemoteLoginBridge.Command(command.id(), active.runId(), active.context(), "login", 61000, callback)));
        assertFalse(RemoteLoginBridge.valid(new RemoteLoginBridge.Command(command.id(), active.runId(), active.context(), "callback", 61000, "x".repeat(4001))));
        var expired = new RemoteLoginBridge.Snapshot(active.runId(), active.context(), active.username(), active.accountId(), "signing_in", "", false, null, false,
                new dev.mcneds.socialxpfarm.auth.BrowserPrompt(active.browserPrompt().authorizationUri(), 1000));
        assertFalse(RemoteLoginBridge.permitted(expired, command, 1000));
    }

}
