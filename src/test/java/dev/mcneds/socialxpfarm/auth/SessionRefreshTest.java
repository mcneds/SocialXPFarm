package dev.mcneds.socialxpfarm.auth;

import net.minecraft.client.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class SessionRefreshTest {
    @TempDir Path temp;
    private final UUID alt = UUID.randomUUID();
    private final User original = user(alt, "synthetic-expired");
    private final AtomicLong clock = new AtomicLong();
    private final AtomicInteger calls = new AtomicInteger();

    private static User user(UUID id, String token) { return new User("Alt", id, token, Optional.empty(), Optional.empty()); }
    private RefreshTokenStore store() { return new RefreshTokenStore(temp.resolve("auth")); }
    private void seed() throws Exception { store().save(new RefreshTokenStore.Credential(alt, "Alt", "synthetic-refresh")); }
    private SessionRefresh.Backend backend(Work work) {
        return new SessionRefresh.Backend() {
            public User refresh(RefreshTokenStore.Credential credential, SessionRefresh.Save save) throws Exception {
                calls.incrementAndGet(); return work.run(credential, save);
            }
            public User pair(User expected, Consumer<URI> browser, SessionRefresh.Save save) throws Exception {
                return work.run(null, save);
            }
        };
    }
    @FunctionalInterface interface Work { User run(RefreshTokenStore.Credential credential, SessionRefresh.Save save) throws Exception; }

    @Test void savedAccountRenewsWithoutBrowserAndOnlyOneRequestRuns() throws Exception {
        seed();
        Queue<Runnable> queue = new ArrayDeque<>();
        var runner = new SessionRefresh(store(), backend((credential, save) -> user(alt, "synthetic-new")), clock::get, queue::add);
        runner.start(original);
        for (int i = 0; i < 100; i++) runner.tick();
        assertEquals(1, queue.size());
        assertEquals(SessionRefresh.Status.RUNNING, runner.status());
        queue.remove().run();
        runner.tick();
        assertEquals(SessionRefresh.Status.READY, runner.status());
        assertEquals("synthetic-new", runner.result().getAccessToken());
        assertEquals(1, calls.get());
    }

    @Test void missingOrWrongInstanceAccountRequestsPairingWithoutNetwork() throws Exception {
        var runner = new SessionRefresh(store(), backend((credential, save) -> { fail("No credential for this account"); return null; }), clock::get, Runnable::run);
        runner.start(original);
        assertEquals(SessionRefresh.Status.NEEDS_LOGIN, runner.status());
        store().save(new RefreshTokenStore.Credential(UUID.randomUUID(), "Other", "synthetic-other"));
        runner.start(original);
        assertEquals(SessionRefresh.Status.NEEDS_LOGIN, runner.status());
        assertEquals(0, calls.get());
    }

    @Test void outageWaitsSixtySecondsAndUsesPersistedRotatedTokenForRetry() throws Exception {
        seed();
        var runner = new SessionRefresh(store(), backend((credential, save) -> {
            if (calls.get() == 1) {
                save.accept(new RefreshTokenStore.Credential(alt, "Alt", "synthetic-rotated"));
                throw new AuthFailure(AuthFailure.Kind.RETRY, "Temporary outage");
            }
            assertEquals("synthetic-rotated", credential.refreshToken());
            return user(alt, "synthetic-new");
        }), clock::get, Runnable::run);
        runner.start(original);
        runner.tick();
        assertEquals(SessionRefresh.Status.RETRY_WAIT, runner.status());
        clock.set(59_999_999_999L);
        runner.tick();
        assertEquals(1, calls.get());
        clock.incrementAndGet();
        runner.tick();
        assertEquals(2, calls.get());
        assertEquals(SessionRefresh.Status.READY, runner.status());
    }

    @Test void revokedConsentDoesNotAutomaticallyRetryOrLaunchBrowserLoops() throws Exception {
        seed();
        var runner = new SessionRefresh(store(), backend((credential, save) -> {
            throw new AuthFailure(AuthFailure.Kind.LOGIN, "Sign in again");
        }), clock::get, Runnable::run);
        runner.start(original);
        runner.tick();
        clock.set(Long.MAX_VALUE / 2);
        for (int i = 0; i < 10; i++) runner.tick();
        assertEquals(SessionRefresh.Status.NEEDS_LOGIN, runner.status());
        assertEquals(1, calls.get());
    }

    @Test void cancelledWorkCannotSaveOrDeliverLateSessionEvenIfItIgnoresInterruption() throws Exception {
        seed();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicBoolean rejectedSave = new AtomicBoolean();
        var runner = new SessionRefresh(store(), backend((credential, save) -> {
            entered.countDown();
            while (release.getCount() > 0) {
                try { release.await(); } catch (InterruptedException ignored) { /* Simulate late network completion. */ }
            }
            try { save.accept(new RefreshTokenStore.Credential(alt, "Alt", "synthetic-too-late")); }
            catch (CancellationException e) { rejectedSave.set(true); }
            finally { finished.countDown(); }
            return user(alt, "synthetic-new");
        }), clock::get, action -> Thread.startVirtualThread(action));
        runner.start(original);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            runner.cancel();
        } finally { release.countDown(); }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        runner.tick();
        assertTrue(rejectedSave.get());
        assertEquals(SessionRefresh.Status.IDLE, runner.status());
        assertNull(runner.result());
        assertEquals("synthetic-refresh", store().load(alt).orElseThrow().refreshToken());
    }

    @Test void wrongAccountResultIsNeverDelivered() throws Exception {
        seed();
        var runner = new SessionRefresh(store(), backend((credential, save) -> user(UUID.randomUUID(), "synthetic-other")), clock::get, Runnable::run);
        runner.start(original);
        runner.tick();
        assertEquals(SessionRefresh.Status.NEEDS_LOGIN, runner.status());
        assertNull(runner.result());
    }

    @Test void unexpectedExceptionDoesNotExposeCredentialInStatus() throws Exception {
        seed();
        var runner = new SessionRefresh(store(), backend((credential, save) -> { throw new Exception("synthetic-secret"); }), clock::get, Runnable::run);
        runner.start(original);
        runner.tick();
        assertEquals(SessionRefresh.Status.FAILED, runner.status());
        assertFalse(runner.message().contains("synthetic-secret"));
    }

    @Test void unchangedSessionWaitsBeforeTryingAnotherRenewal() throws Exception {
        seed();
        var runner = new SessionRefresh(store(), backend((credential, save) -> user(alt, original.getAccessToken())), clock::get, Runnable::run);
        runner.start(original);
        runner.tick();
        assertEquals(SessionRefresh.Status.RETRY_WAIT, runner.status());
        assertNull(runner.result());
        assertEquals(1, calls.get());
    }
}
