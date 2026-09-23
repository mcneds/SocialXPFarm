package dev.mcneds.socialxpfarm.auth;

/** Only fixed, credential-free messages may cross into the UI or logs. */
public final class AuthFailure extends Exception {
    public enum Kind { RETRY, LOGIN, ACCOUNT, LOCAL, PENDING, SLOW_DOWN }
    public final Kind kind;

    public AuthFailure(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }
}
