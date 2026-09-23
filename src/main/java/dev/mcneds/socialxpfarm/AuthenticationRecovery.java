package dev.mcneds.socialxpfarm;

import net.minecraft.client.User;

/** Session gate shared by connection recovery and its headless scenario tests. */
final class AuthenticationRecovery<S> {
    enum Result { IDLE, WAITING, WRONG_ACCOUNT, READY }

    private S parent;
    private User rejected;
    private boolean warnedWrongAccount;

    void begin(S failureScreen, User user, Runnable openLogin) {
        if (rejected != null && parent == failureScreen) return;
        parent = failureScreen;
        rejected = user;
        warnedWrongAccount = false;
        openLogin.run();
    }

    Result poll(S screen, User current) {
        if (rejected == null) return Result.IDLE;
        // Auth Me installs the user before returning to its success screen. Do not interrupt its UI.
        if (screen != parent) return Result.WAITING;
        String token = current.getAccessToken();
        if (current == rejected || token.equals(rejected.getAccessToken())
                || token.isBlank() || token.equals("invalidtoken")) return Result.WAITING;
        // Browser cookies may belong to the main account or another alt.
        if (!current.getProfileId().equals(rejected.getProfileId())) {
            if (warnedWrongAccount) return Result.WAITING;
            warnedWrongAccount = true;
            return Result.WRONG_ACCOUNT;
        }
        clear();
        return Result.READY;
    }

    String expectedName() { return rejected.getName(); }

    void clear() {
        parent = null;
        rejected = null;
        warnedWrongAccount = false;
    }
}
