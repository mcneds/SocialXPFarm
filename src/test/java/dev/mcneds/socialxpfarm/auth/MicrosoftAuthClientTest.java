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

    @Test void renewalUsesTheClientIdBoundToTheSavedRefreshToken() throws Exception {
        String clientId = UUID.randomUUID().toString();
        var client = new MicrosoftAuthClient((endpoint, body, form, bearer) -> {
            if (endpoint.equals(MicrosoftAuthClient.TOKEN)) assertEquals(clientId, OAuthCallback.parseQuery(body).get("client_id"));
            return success(endpoint);
        });
        AtomicReference<RefreshTokenStore.Credential> rotated = new AtomicReference<>();
        User result = client.refresh(new RefreshTokenStore.Credential(ALT, "Alt", "synthetic-old", clientId), rotated::set);
        assertEquals(clientId, rotated.get().clientId());
        assertEquals(ALT, result.getProfileId());
    }
    @Test void pastedBrowserCallbackRedeemsOnceWithPkceAndSavesVerifiedConfiguredClient() throws Exception {
        String clientId = UUID.randomUUID().toString();
        AtomicReference<Map<String, String>> authorization = new AtomicReference<>();
        AtomicReference<RefreshTokenStore.Credential> credential = new AtomicReference<>();
        List<String> calls = new ArrayList<>();
        var client = new MicrosoftAuthClient((endpoint, body, form, bearer) -> {
            calls.add(endpoint);
            assertNull(credential.get());
            if (endpoint.equals(MicrosoftAuthClient.TOKEN)) {
                var fields = OAuthCallback.parseQuery(body);
                assertEquals(clientId, fields.get("client_id"));
                assertEquals("authorization_code", fields.get("grant_type"));
                assertEquals("synthetic-code", fields.get("code"));
                assertEquals(authorization.get().get("redirect_uri"), fields.get("redirect_uri"));
                assertEquals(authorization.get().get("code_challenge"), OAuthCallback.challenge(fields.get("code_verifier")));
            }
            return success(endpoint);
        });
        var expected = new User("Alt", ALT, "synthetic-live", Optional.empty(), Optional.empty());
        User renewed = client.pairBrowser(expected, clientId, receiver -> {
            var params = OAuthCallback.parseQuery(URI.create(receiver.prompt().authorizationUri()).getRawQuery());
            authorization.set(params);
            String address = params.get("redirect_uri") + "?state=" + params.get("state") + "&code=synthetic-code";
            assertTrue(receiver.submit(address));
            assertFalse(receiver.submit(address));
        }, credential::set);
        assertEquals(ALT, renewed.getProfileId());
        assertEquals(clientId, credential.get().clientId());
        assertEquals(1, Collections.frequency(calls, MicrosoftAuthClient.TOKEN));
    }

    @Test void wrongAccountExpiredGrantAndDownstreamOutageNeverSaveOrRedeemTwice() {
        for (String failing : List.of(MicrosoftAuthClient.PROFILE, MicrosoftAuthClient.TOKEN, MicrosoftAuthClient.XBOX)) {
            List<String> calls = new ArrayList<>();
            var client = new MicrosoftAuthClient((endpoint, body, form, bearer) -> {
                calls.add(endpoint);
                if (!endpoint.equals(failing)) return success(endpoint);
                return switch (failing) {
                    case MicrosoftAuthClient.PROFILE -> new MicrosoftAuthClient.Response(200,
                            "{\"id\":\"00000000000000000000000000000002\",\"name\":\"Other\"}");
                    case MicrosoftAuthClient.TOKEN -> new MicrosoftAuthClient.Response(400,
                            "{\"error\":\"invalid_grant\",\"error_description\":\"synthetic-secret\"}");
                    default -> new MicrosoftAuthClient.Response(503, "synthetic-secret");
                };
            });
            var expected = new User("Alt", ALT, "synthetic-live", Optional.empty(), Optional.empty());
            AuthFailure error = assertThrows(AuthFailure.class, () -> client.pairBrowser(expected, MicrosoftAuthClient.CLIENT_ID, receiver -> {
                var params = OAuthCallback.parseQuery(URI.create(receiver.prompt().authorizationUri()).getRawQuery());
                assertTrue(receiver.submit(params.get("redirect_uri") + "?state=" + params.get("state") + "&code=synthetic-code"));
            }, value -> fail("Failed sign-in must not overwrite saved credential")));
            assertFalse(error.toString().contains("synthetic-secret"));
            assertEquals(1, Collections.frequency(calls, MicrosoftAuthClient.TOKEN));
        }
    }

    @Test void httpsCallbackExchangesExactRedirectAndKeepsProofKeyAndTokensOnInstance() throws Exception {
        String clientId = UUID.randomUUID().toString();
        String redirect = "https://auth.example.test/oauth/callback";
        AtomicReference<Map<String, String>> authorization = new AtomicReference<>();
        AtomicReference<RefreshTokenStore.Credential> saved = new AtomicReference<>();
        var client = new MicrosoftAuthClient((endpoint, body, form, bearer) -> {
            assertNull(saved.get());
            if (endpoint.equals(MicrosoftAuthClient.TOKEN)) {
                var fields = OAuthCallback.parseQuery(body);
                assertEquals(redirect, fields.get("redirect_uri"));
                assertEquals(clientId, fields.get("client_id"));
                assertEquals("synthetic-https", fields.get("code"));
                assertEquals(authorization.get().get("code_challenge"), OAuthCallback.challenge(fields.get("code_verifier")));
                assertFalse(fields.containsKey("client_secret"));
            }
            return success(endpoint);
        });
        User expected = new User("Alt", ALT, "synthetic-live", Optional.empty(), Optional.empty());
        User result = client.pairBrowser(expected, clientId, redirect, receiver -> {
            var params = OAuthCallback.parseQuery(URI.create(receiver.prompt().authorizationUri()).getRawQuery());
            authorization.set(params);
            assertEquals("form_post", params.get("response_mode"));
            assertTrue(receiver.submit(redirect + "?state=" + params.get("state") + "&code=synthetic-https"));
        }, saved::set);
        assertEquals(ALT, result.getProfileId());
        assertEquals(clientId, saved.get().clientId());
        assertEquals("synthetic-live", expected.getAccessToken());
    }

    @Test void unapprovedMinecraftRegistrationIsAConfigurationFailureWithoutProviderLeakage() {
        var client = new MicrosoftAuthClient((endpoint, body, form, bearer) -> endpoint.equals(MicrosoftAuthClient.MINECRAFT)
                ? new MicrosoftAuthClient.Response(403, "{\"error\":\"ForbiddenOperationException\",\"errorMessage\":\"Invalid app registration\",\"detail\":\"synthetic-secret\"}")
                : success(endpoint));
        var expected = new User("Alt", ALT, "synthetic-live", Optional.empty(), Optional.empty());
        var error = assertThrows(AuthFailure.class, () -> client.pairBrowser(expected, UUID.randomUUID().toString(),
                "https://auth.example.test/oauth/callback", receiver -> {
                    var fields = OAuthCallback.parseQuery(URI.create(receiver.prompt().authorizationUri()).getRawQuery());
                    assertTrue(receiver.submit(fields.get("redirect_uri") + "?state=" + fields.get("state") + "&code=synthetic-code"));
                }, value -> fail("Rejected registration must not replace credentials")));
        assertEquals(AuthFailure.Kind.LOCAL, error.kind);
        assertTrue(error.getMessage().contains("application registration"));
        assertFalse(error.toString().contains("synthetic-secret"));
    }

}
