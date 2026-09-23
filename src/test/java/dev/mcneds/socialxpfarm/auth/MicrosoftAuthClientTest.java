package dev.mcneds.socialxpfarm.auth;

import net.minecraft.client.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class MicrosoftAuthClientTest {
    private static final UUID ALT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final RefreshTokenStore.Credential saved = new RefreshTokenStore.Credential(ALT, "Alt", "synthetic-old-refresh");

    private MicrosoftAuthClient.Response success(String endpoint) {
        String body = switch (endpoint) {
            case MicrosoftAuthClient.TOKEN -> "{\"access_token\":\"synthetic-ms\",\"refresh_token\":\"synthetic-rotated\"}";
            case MicrosoftAuthClient.XBOX -> "{\"Token\":\"synthetic-xbox\"}";
            case MicrosoftAuthClient.XSTS -> "{\"Token\":\"synthetic-xsts\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"synthetic-hash\"}]}}";
            case MicrosoftAuthClient.MINECRAFT -> "{\"access_token\":\"synthetic-minecraft\"}";
            case MicrosoftAuthClient.PROFILE -> "{\"id\":\"00000000000000000000000000000001\",\"name\":\"Alt\"}";
            default -> throw new AssertionError("Unexpected endpoint");
        };
        return new MicrosoftAuthClient.Response(200, body);
    }

    @Test void silentRefreshExchangesAllTokensAndPersistsRotationBeforeXboxRequests() throws Exception {
        List<String> calls = new ArrayList<>();
        AtomicReference<RefreshTokenStore.Credential> rotated = new AtomicReference<>();
        var client = new MicrosoftAuthClient((endpoint, body, form, bearer) -> {
            calls.add(endpoint);
            if (endpoint.equals(MicrosoftAuthClient.TOKEN)) {
                assertTrue(form);
                var fields = OAuthCallback.parseQuery(body);
                assertEquals("refresh_token", fields.get("grant_type"));
                assertEquals("synthetic-old-refresh", fields.get("refresh_token"));
                assertFalse(fields.containsKey("client_secret"));
            } else assertEquals("synthetic-rotated", rotated.get().refreshToken());
            if (endpoint.equals(MicrosoftAuthClient.PROFILE)) assertEquals("synthetic-minecraft", bearer);
            return success(endpoint);
        });
        User renewed = client.refresh(saved, rotated::set);
        assertEquals(ALT, renewed.getProfileId());
        assertEquals("synthetic-minecraft", renewed.getAccessToken());
        assertEquals(List.of(MicrosoftAuthClient.TOKEN, MicrosoftAuthClient.XBOX, MicrosoftAuthClient.XSTS,
                MicrosoftAuthClient.MINECRAFT, MicrosoftAuthClient.PROFILE), calls);
    }

    @Test void omittedReplacementRefreshTokenRetainsOriginal() throws Exception {
        var client = new MicrosoftAuthClient((endpoint, body, form, bearer) -> endpoint.equals(MicrosoftAuthClient.TOKEN)
                ? new MicrosoftAuthClient.Response(200, "{\"access_token\":\"synthetic-ms\"}") : success(endpoint));
        AtomicReference<RefreshTokenStore.Credential> rotated = new AtomicReference<>();
        client.refresh(saved, rotated::set);
        assertEquals(saved, rotated.get());
    }

    @Test void downstreamOutageDoesNotLoseRotatedRefreshToken() {
        AtomicReference<RefreshTokenStore.Credential> rotated = new AtomicReference<>();
        var client = new MicrosoftAuthClient((endpoint, body, form, bearer) -> endpoint.equals(MicrosoftAuthClient.XBOX)
                ? new MicrosoftAuthClient.Response(503, "synthetic-secret") : success(endpoint));
        AuthFailure failure = assertThrows(AuthFailure.class, () -> client.refresh(saved, rotated::set));
        assertEquals(AuthFailure.Kind.RETRY, failure.kind);
        assertEquals("synthetic-rotated", rotated.get().refreshToken());
        assertFalse(failure.toString().contains("synthetic-secret"));
    }

    @ParameterizedTest @ValueSource(strings = {"invalid_grant", "interaction_required", "login_required", "consent_required"})
    void revokedTokensAndSecurityPromptsNeedOneNewLogin(String code) {
        var client = new MicrosoftAuthClient((endpoint, body, form, bearer) -> new MicrosoftAuthClient.Response(400,
                "{\"error\":\"" + code + "\",\"error_description\":\"synthetic-secret\"}"));
        AuthFailure failure = assertThrows(AuthFailure.class, () -> client.refresh(saved, value -> fail("Do not overwrite credentials")));
        assertEquals(AuthFailure.Kind.LOGIN, failure.kind);
        assertFalse(failure.toString().contains("synthetic-secret"));
    }

    @ParameterizedTest @ValueSource(ints = {408, 429, 500, 503})
    void transientHttpFailuresAreRetried(int status) {
        var client = new MicrosoftAuthClient((endpoint, body, form, bearer) -> new MicrosoftAuthClient.Response(status, "no JSON"));
        assertEquals(AuthFailure.Kind.RETRY, assertThrows(AuthFailure.class, () -> client.refresh(saved, value -> {})).kind);
    }

    @Test void networkAndMalformedResponsesAreSanitized() {
        var network = new MicrosoftAuthClient((endpoint, body, form, bearer) -> { throw new IOException("synthetic-secret"); });
        var malformed = new MicrosoftAuthClient((endpoint, body, form, bearer) -> new MicrosoftAuthClient.Response(200, "synthetic-secret"));
        for (var client : List.of(network, malformed)) {
            AuthFailure error = assertThrows(AuthFailure.class, () -> client.refresh(saved, value -> {}));
            assertEquals(AuthFailure.Kind.RETRY, error.kind);
            assertNull(error.getCause());
            assertFalse(error.toString().contains("synthetic-secret"));
        }
    }

    @Test void differentMinecraftUuidCannotBecomeARefreshedSession() {
        var client = new MicrosoftAuthClient((endpoint, body, form, bearer) -> endpoint.equals(MicrosoftAuthClient.PROFILE)
                ? new MicrosoftAuthClient.Response(200, "{\"id\":\"00000000000000000000000000000002\",\"name\":\"OtherAlt\"}") : success(endpoint));
        assertEquals(AuthFailure.Kind.ACCOUNT, assertThrows(AuthFailure.class, () -> client.refresh(saved, value -> {})).kind);
    }

    @Test void oneTimePairingUsesCallbackPkceAndSavesOnlyTheVerifiedAccount() throws Exception {
        AtomicReference<Map<String, String>> authorization = new AtomicReference<>();
        AtomicReference<RefreshTokenStore.Credential> credential = new AtomicReference<>();
        var client = new MicrosoftAuthClient((endpoint, body, form, bearer) -> {
            if (endpoint.equals(MicrosoftAuthClient.TOKEN)) {
                var fields = OAuthCallback.parseQuery(body);
                assertEquals("authorization_code", fields.get("grant_type"));
                assertEquals("synthetic-code", fields.get("code"));
                assertEquals(authorization.get().get("redirect_uri"), fields.get("redirect_uri"));
                assertEquals(authorization.get().get("code_challenge"), OAuthCallback.challenge(fields.get("code_verifier")));
            }
            assertNull(credential.get()); // Do not save until the profile has been verified.
            return success(endpoint);
        });
        var expected = new User("Alt", ALT, "synthetic-expired", Optional.empty(), Optional.empty());
        User renewed = client.pair(expected, uri -> {
            var params = OAuthCallback.parseQuery(uri.getRawQuery());
            authorization.set(params);
            try (var http = HttpClient.newHttpClient()) {
                http.send(HttpRequest.newBuilder(URI.create(params.get("redirect_uri") + "?state=" + params.get("state")
                        + "&code=synthetic-code")).GET().build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) { throw new AssertionError(e); }
        }, credential::set);
        assertEquals(ALT, renewed.getProfileId());
        assertEquals(ALT, credential.get().uuid());
        assertEquals("synthetic-rotated", credential.get().refreshToken());
    }

    @Test void wrongAccountDuringPairingNeverOverwritesSavedCredentials() {
        var client = new MicrosoftAuthClient((endpoint, body, form, bearer) -> success(endpoint));
        User otherInstance = new User("Other", UUID.randomUUID(), "synthetic-expired", Optional.empty(), Optional.empty());
        AuthFailure error = assertThrows(AuthFailure.class, () -> client.pair(otherInstance, uri -> {
            var params = OAuthCallback.parseQuery(uri.getRawQuery());
            try (var http = HttpClient.newHttpClient()) {
                http.send(HttpRequest.newBuilder(URI.create(params.get("redirect_uri") + "?state=" + params.get("state")
                        + "&code=synthetic-code")).GET().build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) { throw new AssertionError(e); }
        }, value -> fail("Wrong account must not be saved")));
        assertEquals(AuthFailure.Kind.ACCOUNT, error.kind);
    }

    @Test void invalidOAuthRegistrationDoesNotRequestRepeatedSignIns() {
        var client = new MicrosoftAuthClient((endpoint, body, form, bearer) -> new MicrosoftAuthClient.Response(400,
                "{\"error\":\"invalid_client\"}"));
        assertEquals(AuthFailure.Kind.LOCAL, assertThrows(AuthFailure.class, () -> client.refresh(saved, value -> {})).kind);
    }
}
