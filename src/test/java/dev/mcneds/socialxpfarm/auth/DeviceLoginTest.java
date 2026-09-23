package dev.mcneds.socialxpfarm.auth;

import net.minecraft.client.User;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DeviceLoginTest {
    private final UUID alt = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final User expected = new User("Alt", alt, "synthetic-old", Optional.empty(), Optional.empty());
    private final AtomicLong clock = new AtomicLong();
    private final List<Long> sleeps = new ArrayList<>();
    private final List<String> grants = new ArrayList<>();
    private final AtomicReference<DevicePrompt> prompt = new AtomicReference<>();
    private final AtomicReference<RefreshTokenStore.Credential> saved = new AtomicReference<>();
    private final Queue<String> errors = new ArrayDeque<>();
    private String profile = "00000000000000000000000000000001";
    private long expires = 900;
    private boolean downstreamFails;

    private MicrosoftAuthClient client() {
        return new MicrosoftAuthClient((endpoint, body, form, bearer) -> {
            if (endpoint.equals(MicrosoftAuthClient.DEVICE)) {
                assertEquals(MicrosoftAuthClient.CLIENT_ID, OAuthCallback.parseQuery(body).get("client_id"));
                return response(200, "{\"device_code\":\"private-synthetic-code\",\"user_code\":\"ABCD-EFGH\","
                        + "\"verification_uri\":\"https://microsoft.com/devicelogin\",\"expires_in\":" + expires + ",\"interval\":5}");
            }
            if (endpoint.equals(MicrosoftAuthClient.TOKEN)) {
                var fields = OAuthCallback.parseQuery(body);
                grants.add(fields.get("grant_type"));
                assertEquals("private-synthetic-code", fields.get("device_code"));
                if (!errors.isEmpty()) return response(400, "{\"error\":\"" + errors.remove() + "\",\"error_description\":\"sensitive\"}");
                return response(200, "{\"access_token\":\"synthetic-ms\",\"refresh_token\":\"synthetic-refresh\"}");
            }
            if (endpoint.equals(MicrosoftAuthClient.XBOX)) return downstreamFails ? response(503, "sensitive") : response(200, "{\"Token\":\"synthetic-xbox\"}");
            if (endpoint.equals(MicrosoftAuthClient.XSTS)) return response(200, "{\"Token\":\"synthetic-xsts\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"hash\"}]}}");
            if (endpoint.equals(MicrosoftAuthClient.MINECRAFT)) return response(200, "{\"access_token\":\"synthetic-mc\"}");
            return response(200, "{\"id\":\"" + profile + "\",\"name\":\"Alt\"}");
        }, clock::get, millis -> { sleeps.add(millis); clock.addAndGet(millis * 1_000_000); });
    }
    private static MicrosoftAuthClient.Response response(int status, String json) { return new MicrosoftAuthClient.Response(status, json); }
    private User run() throws Exception { return client().pairDevice(expected, MicrosoftAuthClient.CLIENT_ID, prompt::set, saved::set); }

    @Test void pendingThenSuccessStoresOnlyExpectedAccountAndExposesOnlyUserCode() throws Exception {
        errors.add("authorization_pending");
        User renewed = run();
        assertEquals(alt, renewed.getProfileId());
        assertEquals(alt, saved.get().uuid());
        assertEquals(MicrosoftAuthClient.CLIENT_ID, saved.get().clientId());
        assertEquals("ABCD-EFGH", prompt.get().userCode());
        assertFalse(prompt.get().toString().contains("ABCD"));
        assertEquals(List.of(5000L, 5000L), sleeps);
        assertTrue(grants.stream().allMatch("urn:ietf:params:oauth:grant-type:device_code"::equals));
    }

    @Test void slowdownPermanentlyIncreasesPollingInterval() throws Exception {
        errors.addAll(List.of("slow_down", "authorization_pending", "slow_down"));
        run();
        assertEquals(List.of(5000L, 10000L, 10000L, 15000L), sleeps);
    }

    @Test void temporaryOutageWaitsBeforeContinuingSameGrant() throws Exception {
        errors.add("temporarily_unavailable");
        run();
        assertEquals(List.of(5000L, 60000L), sleeps);
    }

    @Test void expiryNeverRegeneratesOrPollsAnExpiredCode() {
        expires = 10;
        errors.add("authorization_pending");
        assertEquals(AuthFailure.Kind.LOGIN, assertThrows(AuthFailure.class, this::run).kind);
        assertEquals(1, grants.size());
        assertEquals(List.of(5000L, 5000L), sleeps);
        assertNull(saved.get());
    }

    @ParameterizedTest @ValueSource(strings = {"authorization_declined", "access_denied", "expired_token", "bad_verification_code"})
    void terminalDeviceErrorsStopWithoutSavingOrLeakingResponses(String error) {
        errors.add(error);
        AuthFailure failure = assertThrows(AuthFailure.class, this::run);
        assertEquals(AuthFailure.Kind.LOGIN, failure.kind);
        assertFalse(failure.toString().contains("sensitive"));
        assertEquals(1, grants.size());
        assertNull(saved.get());
    }

    @Test void wrongAccountNeverReplacesSavedLogin() {
        profile = "00000000000000000000000000000002";
        assertEquals(AuthFailure.Kind.ACCOUNT, assertThrows(AuthFailure.class, this::run).kind);
        assertNull(saved.get());
    }

    @Test void downstreamErrorDoesNotRedeemConsumedDeviceGrantTwice() {
        downstreamFails = true;
        assertEquals(AuthFailure.Kind.RETRY, assertThrows(AuthFailure.class, this::run).kind);
        assertEquals(1, grants.size());
        assertNull(saved.get());
    }

    @Test void interruptionStopsBeforeAnyTokenPolling() {
        var client = new MicrosoftAuthClient((endpoint, body, form, bearer) -> response(200,
                "{\"device_code\":\"private\",\"user_code\":\"ABCDEFGH\",\"verification_uri\":\"https://microsoft.com/devicelogin\",\"expires_in\":900}"),
                clock::get, millis -> { throw new InterruptedException(); });
        assertThrows(InterruptedException.class, () -> client.pairDevice(expected, MicrosoftAuthClient.CLIENT_ID, prompt::set, saved::set));
        assertNull(saved.get());
    }

    @ParameterizedTest @ValueSource(strings = {"http://microsoft.com/devicelogin", "https://microsoft.com.attacker.test/", "https://evil.test/", "https://user@microsoft.com/", "https://microsoft.com:1234/"})
    void phoneLinkMustBeHttpsOnAnExactMicrosoftHost(String uri) {
        assertThrows(IllegalArgumentException.class, () -> new DevicePrompt("ABCD-EFGH", uri, 123));
    }

    @ParameterizedTest @ValueSource(strings = {"unauthorized_client", "invalid_client", "invalid_scope", "unsupported_grant_type"})
    void unsupportedDeviceRegistrationStopsBeforePublishingPrompt(String error) {
        AtomicInteger requests = new AtomicInteger();
        var client = new MicrosoftAuthClient((endpoint, body, form, bearer) -> {
            requests.incrementAndGet();
            return response(400, "{\"error\":\"" + error + "\"}");
        });
        AuthFailure failure = assertThrows(AuthFailure.class,
                () -> client.pairDevice(expected, MicrosoftAuthClient.CLIENT_ID, prompt::set, saved::set));
        assertEquals(AuthFailure.Kind.LOCAL, failure.kind);
        assertNull(prompt.get());
        assertNull(saved.get());
        assertEquals(1, requests.get());
    }
}
