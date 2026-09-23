package dev.mcneds.socialxpfarm.auth;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

class OAuthCallbackTest {
    private int send(OAuthCallback callback, String query) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(callback.redirect() + "?" + query)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    @Test void rejectsWrongStateWithoutConsumingLoginThenAcceptsMatchingCallback() throws Exception {
        try (var callback = new OAuthCallback()) {
            assertEquals(400, send(callback, "code=untrusted"));
            assertEquals(400, send(callback, "state=wrong&code=untrusted"));
            assertEquals(400, send(callback, "state=" + callback.state + "&state=duplicate&code=untrusted"));
            assertEquals(200, send(callback, "state=" + callback.state + "&code=synthetic-code"));
            assertEquals("synthetic-code", callback.awaitCode());
        }
    }

    @Test void simultaneousInstancesHaveIndependentPortsStateAndProofKeys() throws Exception {
        try (var first = new OAuthCallback(); var second = new OAuthCallback()) {
            assertNotEquals(first.redirect(), second.redirect());
            assertNotEquals(first.state, second.state);
            assertNotEquals(first.verifier, second.verifier);
            assertEquals(400, send(first, "state=" + second.state + "&code=wrong-instance"));
            send(first, "state=" + first.state + "&code=alt-a");
            send(second, "state=" + second.state + "&code=alt-b");
            assertEquals("alt-a", first.awaitCode());
            assertEquals("alt-b", second.awaitCode());
        }
    }

    @Test void initialConsentRequestsAccountSelectionOfflineAccessAndPkce() throws Exception {
        try (var callback = new OAuthCallback()) {
            var params = OAuthCallback.parseQuery(callback.authorizeUri().getRawQuery());
            assertEquals("select_account", params.get("prompt"));
            assertEquals("XboxLive.signin offline_access", params.get("scope"));
            assertEquals("S256", params.get("code_challenge_method"));
            assertEquals(OAuthCallback.challenge(callback.verifier), params.get("code_challenge"));
            assertEquals(callback.redirect(), params.get("redirect_uri"));
            assertFalse(params.containsKey("code_verifier"));
        }
        // RFC 7636 Appendix B test vector.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                OAuthCallback.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test void deniedConsentReturnsSafeErrorWithoutProviderDescription() throws Exception {
        try (var callback = new OAuthCallback()) {
            send(callback, "state=" + callback.state + "&error=access_denied&error_description=synthetic-secret");
            var error = assertThrows(AuthFailure.class, callback::awaitCode);
            assertEquals(AuthFailure.Kind.LOGIN, error.kind);
            assertFalse(error.toString().contains("synthetic-secret"));
        }
    }
}
