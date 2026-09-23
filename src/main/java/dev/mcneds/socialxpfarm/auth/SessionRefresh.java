package dev.mcneds.socialxpfarm.auth;

import net.minecraft.client.User;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.*;
import java.util.function.*;

/** Single-flight background work; cancelling prevents late session delivery and credential writes. */
public final class SessionRefresh {
    public enum Status { IDLE, RUNNING, RETRY_WAIT, NEEDS_LOGIN, READY, FAILED }
    @FunctionalInterface public interface Save { void accept(RefreshTokenStore.Credential credential) throws IOException; }
    public interface Backend {
        User refresh(RefreshTokenStore.Credential saved, Save save) throws Exception;
        User pair(User expected, Consumer<URI> browser, Save save) throws Exception;
        default User pairBrowser(User expected, String clientId, Consumer<BrowserLogin> display, Save save) throws Exception {
            throw new AuthFailure(AuthFailure.Kind.LOCAL, "Browser sign-in is unavailable in this backend.");
        }
        default User pairDevice(User expected, String clientId, Consumer<DevicePrompt> display, Save save) throws Exception {
            throw new AuthFailure(AuthFailure.Kind.LOCAL, "Phone sign-in is unavailable in this backend.");
        }
    }

    private final RefreshTokenStore store;
    private final Backend backend;
    private final LongSupplier clock;
    private final Executor executor;
    private FutureTask<User> task;
    private User expected;
    private User result;
    private long generation;
    private long retryStarted;
    private boolean pairing;
    private Status status = Status.IDLE;
    private String message = "";
    private DevicePrompt devicePrompt;
    private BrowserLogin browserLogin;

    public SessionRefresh(RefreshTokenStore store, Backend backend) {
        this(store, backend, System::nanoTime, action -> Thread.startVirtualThread(action));
    }

    SessionRefresh(RefreshTokenStore store, Backend backend, LongSupplier clock, Executor executor) {
        this.store = store;
        this.backend = backend;
        this.clock = clock;
        this.executor = executor;
    }

    public synchronized void start(User user) {
        cancel();
        expected = user;
        refresh();
    }

    private void refresh() {
        try {
            var credential = store.load(expected.getProfileId());
            if (credential.isEmpty()) {
                status = Status.NEEDS_LOGIN;
                message = "Sign in once to enable automatic renewal for " + expected.getName() + ".";
                return;
            }
            launch(false, save -> backend.refresh(credential.get(), save));
        } catch (IOException e) {
            status = Status.FAILED;
            message = "Saved account unavailable. Check file permissions or pair again.";
        }
    }

    public synchronized void pair(User user, Consumer<URI> browser) {
        cancel();
        expected = user;
        long operation = generation;
        launch(true, save -> backend.pair(user, uri -> {
            synchronized (this) {
                if (operation != generation || Thread.currentThread().isInterrupted()) throw new CancellationException();
                browser.accept(uri);
            }
        }, save));
    }

    public synchronized void pairDevice(User user, String clientId) {
        cancel();
        expected = user;
        long operation = generation;
        launch(true, save -> backend.pairDevice(user, clientId, prompt -> {
            synchronized (this) {
                if (operation != generation || Thread.currentThread().isInterrupted()) throw new CancellationException();
                devicePrompt = prompt;
            }
        }, save));
        message = "Waiting for Microsoft sign-in from your phone.";
    }

    public synchronized void pairBrowser(User user, String clientId) {
        cancel();
        expected = user;
        long operation = generation;
        launch(true, save -> backend.pairBrowser(user, clientId, login -> {
            synchronized (this) {
                if (operation != generation || Thread.currentThread().isInterrupted()) {
                    login.close();
                    throw new CancellationException();
                }
                browserLogin = login;
            }
        }, save));
    }

    public synchronized BrowserPrompt browserPrompt() {
        return status == Status.RUNNING && browserLogin != null ? browserLogin.prompt() : null;
    }

    public synchronized boolean submitCallback(String address) {
        return status == Status.RUNNING && browserLogin != null && browserLogin.submit(address);
    }

    public synchronized DevicePrompt devicePrompt() { return status == Status.RUNNING ? devicePrompt : null; }

    @FunctionalInterface private interface Work { User run(Save save) throws Exception; }

    private void launch(boolean pairing, Work work) {
        this.pairing = pairing;
        status = Status.RUNNING;
        message = pairing ? "Choose this instance's account in your browser." : "Renewing this instance's session automatically...";
        long operation = generation;
        User original = expected;
        task = new FutureTask<>(() -> {
            User renewed = work.run(credential -> {
                synchronized (this) {
                    if (operation != generation || Thread.currentThread().isInterrupted()) throw new CancellationException();
                    if (!credential.uuid().equals(original.getProfileId())) throw new IOException("Account mismatch");
                    store.save(credential);
                }
            });
            if (!renewed.getProfileId().equals(original.getProfileId()))
                throw new AuthFailure(AuthFailure.Kind.ACCOUNT, "Wrong account. Pair this instance again.");
            if (renewed.getAccessToken().isBlank() || renewed.getAccessToken().equals("invalidtoken"))
                throw new AuthFailure(AuthFailure.Kind.LOGIN, "No online session returned. Pair this instance again.");
            if (!pairing && renewed.getAccessToken().equals(original.getAccessToken()))
                throw new AuthFailure(AuthFailure.Kind.RETRY, "Session was not renewed; retrying in 60 seconds.");
            return renewed;
        });
        executor.execute(task);
    }

    public synchronized void tick() {
        if (status == Status.RETRY_WAIT && clock.getAsLong() - retryStarted >= 60_000_000_000L) refresh();
        if (status != Status.RUNNING || task == null || !task.isDone()) return;
        if (browserLogin != null) browserLogin.close();
        browserLogin = null;
        try {
            result = task.get();
            status = Status.READY;
            message = "Automatic login ready for " + result.getName() + ".";
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AuthFailure failure) {
                message = pairing && failure.kind == AuthFailure.Kind.RETRY
                        ? "Sign-in request failed. Check connectivity, then sign in again." : failure.getMessage();
                if (failure.kind == AuthFailure.Kind.RETRY && !pairing) {
                    status = Status.RETRY_WAIT;
                    retryStarted = clock.getAsLong();
                } else status = failure.kind == AuthFailure.Kind.LOCAL ? Status.FAILED : Status.NEEDS_LOGIN;
            } else {
                status = Status.FAILED;
                message = "Account renewal could not complete. Check storage or pair again.";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel();
        } catch (CancellationException e) { cancel(); }
    }

    public synchronized Status status() { return status; }
    public synchronized String message() { return message; }
    public synchronized User result() { return status == Status.READY ? result : null; }

    public synchronized void cancel() {
        generation++;
        if (task != null) task.cancel(true);
        task = null;
        result = null;
        devicePrompt = null;
        if (browserLogin != null) browserLogin.close();
        browserLogin = null;
        expected = null;
        status = Status.IDLE;
        message = "";
    }
}
