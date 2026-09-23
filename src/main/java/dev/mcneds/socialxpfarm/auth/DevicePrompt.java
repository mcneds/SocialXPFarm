package dev.mcneds.socialxpfarm.auth;

import java.net.URI;
import java.util.Set;

/** The only OAuth fields permitted to leave the instance for display on the owner's phone. */
public record DevicePrompt(String userCode, String verificationUri, long expiresAt) {
    public DevicePrompt {
        URI uri = URI.create(verificationUri);
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                || !Set.of("microsoft.com", "www.microsoft.com", "login.microsoftonline.com", "login.live.com").contains(uri.getHost())
                || !userCode.matches("[A-Za-z0-9-]{4,32}")) throw new IllegalArgumentException("Invalid device prompt");
    }
    @Override public String toString() { return "DevicePrompt[code=REDACTED, expiresAt=" + expiresAt + "]"; }
}
