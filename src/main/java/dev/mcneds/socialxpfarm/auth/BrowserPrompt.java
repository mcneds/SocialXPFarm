package dev.mcneds.socialxpfarm.auth;

/** Public PKCE authorization request; never contains the verifier or returned authorization code. */
public record BrowserPrompt(String authorizationUri, long expiresAt) {
    @Override public String toString() { return "BrowserPrompt[authorizationUri=REDACTED, expiresAt=" + expiresAt + "]"; }
}
