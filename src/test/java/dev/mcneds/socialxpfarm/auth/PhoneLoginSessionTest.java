package dev.mcneds.socialxpfarm.auth;

import net.minecraft.client.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.URI;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class PhoneLoginSessionTest {
    @TempDir Path temp;
    private final User original = new User("Alt", UUID.randomUUID(), "synthetic-live", Optional.empty(), Optional.empty());
    private final Queue<Runnable> work = new ArrayDeque<>();
    private RefreshTokenStore store() { return new RefreshTokenStore(temp.resolve("auth")); }
    @FunctionalInterface interface DeviceWork {
        User run(User expected, Consumer<DevicePrompt> display, SessionRefresh.Save save) throws Exception;
    }
    private PhoneLoginSession session(DeviceWork device) {
        var backend = new SessionRefresh.Backend() {
            public User refresh(RefreshTokenStore.Credential saved, SessionRefresh.Save save) { throw new AssertionError("Test must bypass silent refresh"); }
            public User pair(User expected, Consumer<URI> browser, SessionRefresh.Save save) { throw new AssertionError("Test must never launch desktop browser"); }
            public User pairDevice(User expected, String clientId, Consumer<DevicePrompt> display, SessionRefresh.Save save) throws Exception {
                assertEquals(MicrosoftAuthClient.CLIENT_ID, clientId);
                return device.run(expected, display, save);
            }
        };
        return new PhoneLoginSession(new SessionRefresh(store(), backend, () -> 0, work::add), original, MicrosoftAuthClient.CLIENT_ID);
    }
    private PhoneLoginSession successful() {
        return session((expected, display, save) -> {
            display.accept(new DevicePrompt("TEST-CODE", "https://microsoft.com/devicelogin", 2_000_000_000_000L));
            save.accept(new RefreshTokenStore.Credential(expected.getProfileId(), expected.getName(), "synthetic-new-refresh"));
            return new User(expected.getName(), expected.getProfileId(), "synthetic-renewed", Optional.empty(), Optional.empty());
        });
    }

    @Test void creatingTestRequestsNotificationWithoutStartingGrantOrDeletingSavedCredential() throws Exception {
        store().save(new RefreshTokenStore.Credential(original.getProfileId(), "Alt", "synthetic-existing"));
        var session = successful();
        session.tick(true, original);
        assertEquals("needs_login", session.state());
        assertTrue(session.canLogin());
        assertTrue(work.isEmpty());
        assertEquals("synthetic-existing", store().load(original.getProfileId()).orElseThrow().refreshToken());
    }

    @Test void successfulPhoneLoginSavesCredentialAndKeepsOriginalSession() throws Exception {
        var session = successful();
        session.command(session.context(), "login");
        assertEquals("signing_in", session.state());
        work.remove().run();
        assertNotNull(session.prompt());
        session.tick(true, original);
        assertEquals("paired", session.state());
        assertFalse(session.canLogin());
        assertNull(session.prompt());
        assertEquals("synthetic-live", original.getAccessToken());
        assertSame(original, session.expected());
        assertEquals("synthetic-new-refresh", store().load(original.getProfileId()).orElseThrow().refreshToken());
        session.command(session.context(), "login");
        assertTrue(work.isEmpty());
    }

    @Test void repeatedAndStaleCommandsDoNotCreateExtraGrants() {
        var session = successful();
        String old = session.context();
        session.command(UUID.randomUUID().toString(), "login");
        assertTrue(work.isEmpty());
        session.command(old, "login");
        session.command(old, "login");
        session.command(session.context(), "login");
        assertEquals(1, work.size());
        assertNotEquals(old, session.context());
    }

    @Test void cancelAndRetryUseFreshContextsAndCancelledWorkCannotSave() throws Exception {
        var session = successful();
        session.command(session.context(), "login");
        String old = session.context();
        session.command(old, "cancel");
        assertEquals("cancelled", session.state());
        assertTrue(session.canLogin());
        assertNotEquals(old, session.context());
        work.remove().run();
        assertTrue(store().load(original.getProfileId()).isEmpty());
        session.command(old, "login");
        assertTrue(work.isEmpty());
        session.command(session.context(), "login");
        work.remove().run();
        session.tick(true, original);
        assertEquals("paired", session.state());
    }

    @Test void disabledAutomationOrChangedAccountPreventsPendingWrites() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            var session = successful();
            session.command(session.context(), "login");
            User changed = new User("Other", UUID.randomUUID(), "synthetic-other", Optional.empty(), Optional.empty());
            session.tick(enabled, enabled ? changed : original);
            work.remove().run();
            assertFalse(session.canLogin());
            assertNull(session.prompt());
            assertTrue(store().load(original.getProfileId()).isEmpty());
            session.command(session.context(), "login");
            assertTrue(work.isEmpty());
        }
    }

    @Test void disconnectedOrClosedTestCannotRestartOrSave() throws Exception {
        var session = successful();
        session.command(session.context(), "login");
        session.close();
        work.remove().run();
        session.command(session.context(), "login");
        assertFalse(session.canLogin());
        assertNull(session.prompt());
        assertTrue(work.isEmpty());
        assertTrue(store().load(original.getProfileId()).isEmpty());
    }

    @Test void wrongAccountAndExpiredCodesAllowDeliberateRetryWithoutReplacingSavedCredential() throws Exception {
        store().save(new RefreshTokenStore.Credential(original.getProfileId(), "Alt", "synthetic-existing"));
        for (var kind : new AuthFailure.Kind[]{AuthFailure.Kind.ACCOUNT, AuthFailure.Kind.LOGIN}) {
            var session = session((expected, display, save) -> { throw new AuthFailure(kind, "Synthetic failure"); });
            session.command(session.context(), "login");
            work.remove().run();
            session.tick(true, original);
            assertEquals("needs_login", session.state());
            assertTrue(session.canLogin());
            assertNull(session.prompt());
            assertEquals("synthetic-existing", store().load(original.getProfileId()).orElseThrow().refreshToken());
        }
    }
}
