package dev.mcneds.socialxpfarm.auth;

/** An instance-local, single-use callback receiver. No network requests are made by submit. */
public interface BrowserLogin extends AutoCloseable {
    static String validateRedirect(String value) { return OAuthCallback.validatePublicRedirect(value); }
    BrowserPrompt prompt();
    boolean submit(String address);
    @Override void close();
}
