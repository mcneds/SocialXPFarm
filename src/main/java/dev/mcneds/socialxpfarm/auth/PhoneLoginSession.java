package dev.mcneds.socialxpfarm.auth;

import net.minecraft.client.User;
import java.util.UUID;

/** Explicit phone pairing: validates and saves credentials without replacing a live game session. */
public final class PhoneLoginSession {
    private final SessionRefresh refresh;
    private final User expected;
    private final String clientId;
    private String context = UUID.randomUUID().toString();
    private String state = "needs_login";
    private String message = "Open the bot DM on your phone and tap Sign in.";
    private boolean closed;

    public PhoneLoginSession(SessionRefresh refresh, User expected, String clientId) {
        this.refresh = refresh;
        this.expected = expected;
        this.clientId = clientId;
    }

    public void tick(boolean enabled, User current) {
        if (closed) return;
        if (!enabled || current != expected) {
            close();
            state = enabled ? "cancelled" : "disabled";
            message = "Phone pairing stopped because automation or the current account changed.";
            return;
        }
        if (!state.equals("signing_in")) return;
        refresh.tick();
        message = refresh.message();
        switch (refresh.status()) {
            case READY -> {
                state = "paired";
                message = "Phone sign-in verified; automatic renewal saved. Current connection kept.";
            }
            case NEEDS_LOGIN -> state = "needs_login";
            case FAILED -> state = "failed";
            default -> { }
        }
    }

    public void command(String requestContext, String action) {
        if (closed || !context.equals(requestContext)) return;
        if (action.equals("login") && canLogin()) {
            context = UUID.randomUUID().toString();
            state = "signing_in";
            refresh.pairDevice(expected, clientId);
            message = "Waiting for Microsoft sign-in from your phone.";
        } else if (action.equals("cancel") && state.equals("signing_in")) {
            refresh.cancel();
            context = UUID.randomUUID().toString();
            state = "cancelled";
            message = "Phone sign-in cancelled. Tap Sign in in Discord to try again.";
        }
    }

    public void close() {
        refresh.cancel();
        closed = true;
    }

    public User expected() { return expected; }
    public String context() { return context; }
    public String state() { return state; }
    public String message() { return message; }
    public boolean canLogin() { return !closed && (state.equals("needs_login") || state.equals("cancelled")); }
    public DevicePrompt prompt() { return !closed && state.equals("signing_in") ? refresh.devicePrompt() : null; }
}
