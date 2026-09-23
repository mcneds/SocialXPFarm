package dev.mcneds.socialxpfarm.auth;

import com.google.gson.*;
import net.minecraft.client.User;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/** OAuth renewal plus Xbox/Minecraft exchange. No credentials or provider response bodies are logged. */
public final class MicrosoftAuthClient implements SessionRefresh.Backend {
    // Auth Me's public desktop client registration, used with its session installation API.
    public static final String CLIENT_ID = "e16699bb-2aa8-46da-b5e3-45cbcce29091";
    static final String AUTHORIZE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
    static final String TOKEN = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    static final String DEVICE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    static final String XBOX = "https://user.auth.xboxlive.com/user/authenticate";
    static final String XSTS = "https://xsts.auth.xboxlive.com/xsts/authorize";
    static final String MINECRAFT = "https://api.minecraftservices.com/authentication/login_with_xbox";
    static final String PROFILE = "https://api.minecraftservices.com/minecraft/profile";

    record Response(int status, String body) {
        @Override public String toString() { return "Response[status=" + status + ", body=REDACTED]"; }
    }
    @FunctionalInterface interface Transport {
        Response send(String endpoint, String body, boolean form, String bearer) throws IOException, InterruptedException;
    }
    private final Transport transport;
    private LongSupplier clock = System::nanoTime;
    @FunctionalInterface interface Sleep { void milliseconds(long milliseconds) throws InterruptedException; }
    private Sleep sleep = Thread::sleep;

    public MicrosoftAuthClient() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        transport = (endpoint, body, form, bearer) -> {
            var builder = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(30));
            if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
            if (body == null) builder.GET();
            else builder.header("Content-Type", form ? "application/x-www-form-urlencoded" : "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        };
    }

    MicrosoftAuthClient(Transport transport) { this.transport = transport; }
    MicrosoftAuthClient(Transport transport, LongSupplier clock, Sleep sleep) {
        this.transport = transport;
        this.clock = clock;
        this.sleep = sleep;
    }

    @Override public User refresh(RefreshTokenStore.Credential saved, SessionRefresh.Save save) throws Exception {
        JsonObject tokens = request(TOKEN, form(Map.of("client_id", saved.clientId(), "grant_type", "refresh_token",
                "refresh_token", saved.refreshToken(), "scope", "XboxLive.signin offline_access")), true, null);
        String access = required(tokens, "access_token");
        String rotated = tokens.has("refresh_token") ? required(tokens, "refresh_token") : saved.refreshToken();
        // Persist rotation before Xbox/profile calls, which can fail after Microsoft already renewed it.
        save.accept(new RefreshTokenStore.Credential(saved.uuid(), saved.name(), rotated, saved.clientId()));
        return minecraftUser(access, saved.uuid(), saved.clientId());
    }

    @Override public User pair(User expected, Consumer<URI> browser, SessionRefresh.Save save) throws Exception {
        try (OAuthCallback callback = new OAuthCallback()) {
            browser.accept(callback.authorizeUri());
            return finishBrowserPair(expected, CLIENT_ID, callback, save);
        }
    }

    @Override public User pairBrowser(User expected, String clientId, Consumer<BrowserLogin> display, SessionRefresh.Save save) throws Exception {
        return pairBrowser(expected, clientId, null, display, save);
    }

    @Override public User pairBrowser(User expected, String clientId, String redirectUri, Consumer<BrowserLogin> display, SessionRefresh.Save save) throws Exception {
        try (OAuthCallback callback = new OAuthCallback(clientId, redirectUri)) {
            display.accept(callback);
            return finishBrowserPair(expected, clientId, callback, save);
        }
    }

    private User finishBrowserPair(User expected, String clientId, OAuthCallback callback, SessionRefresh.Save save) throws Exception {
        String code = callback.awaitCode();
        JsonObject tokens = request(TOKEN, form(Map.of("client_id", clientId, "grant_type", "authorization_code",
                "code", code, "redirect_uri", callback.redirect(), "code_verifier", callback.verifier)), true, null);
        User user = minecraftUser(required(tokens, "access_token"), expected.getProfileId(), clientId);
        save.accept(new RefreshTokenStore.Credential(user.getProfileId(), user.getName(), required(tokens, "refresh_token"), clientId));
        return user;
    }

    @Override public User pairDevice(User expected, String clientId, Consumer<DevicePrompt> display, SessionRefresh.Save save) throws Exception {
        UUID.fromString(clientId);
        JsonObject challenge = request(DEVICE, form(Map.of("client_id", clientId, "scope", "XboxLive.signin offline_access")), true, null);
        long duration = positiveSeconds(challenge, "expires_in") * 1_000_000_000L;
        long interval = challenge.has("interval") ? positiveSeconds(challenge, "interval") : 5;
        long started = clock.getAsLong();
        String deviceCode = required(challenge, "device_code");
        try {
            display.accept(new DevicePrompt(required(challenge, "user_code"), required(challenge, "verification_uri"),
                    System.currentTimeMillis() + duration / 1_000_000));
        } catch (IllegalArgumentException e) { throw malformed(); }
        while (clock.getAsLong() - started < duration) {
            long remaining = duration - (clock.getAsLong() - started);
            sleep.milliseconds(Math.min(interval * 1000, Math.max(1, (remaining + 999_999) / 1_000_000)));
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (clock.getAsLong() - started >= duration) break;
            JsonObject tokens;
            try {
                tokens = request(TOKEN, form(Map.of("client_id", clientId,
                        "grant_type", "urn:ietf:params:oauth:grant-type:device_code", "device_code", deviceCode)), true, null, true);
            } catch (AuthFailure failure) {
                if (failure.kind == AuthFailure.Kind.SLOW_DOWN) interval += 5;
                else if (failure.kind == AuthFailure.Kind.RETRY) interval = Math.max(interval, 60);
                else if (failure.kind != AuthFailure.Kind.PENDING) throw failure;
                continue;
            }
            // A device grant is consumed once. Never redeem it again after a downstream error.
            User user = minecraftUser(required(tokens, "access_token"), expected.getProfileId(), clientId);
            save.accept(new RefreshTokenStore.Credential(user.getProfileId(), user.getName(), required(tokens, "refresh_token"), clientId));
            return user;
        }
        throw new AuthFailure(AuthFailure.Kind.LOGIN, "Phone sign-in expired. Request a new code when ready.");
    }

    private static long positiveSeconds(JsonObject json, String field) throws AuthFailure {
        try {
            long value = json.get(field).getAsLong();
            if (value < 1 || value > 3600) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException e) { throw malformed(); }
    }

    private User minecraftUser(String access, UUID expected, String clientId) throws Exception {
        JsonObject xboxProperties = new JsonObject();
        xboxProperties.addProperty("AuthMethod", "RPS");
        xboxProperties.addProperty("SiteName", "user.auth.xboxlive.com");
        xboxProperties.addProperty("RpsTicket", "d=" + access);
        JsonObject xbox = request(XBOX, envelope(xboxProperties, "http://auth.xboxlive.com").toString(), false, null);
        JsonObject xstsProperties = new JsonObject();
        xstsProperties.addProperty("SandboxId", "RETAIL");
        JsonArray userTokens = new JsonArray();
        userTokens.add(required(xbox, "Token"));
        xstsProperties.add("UserTokens", userTokens);
        JsonObject xsts = request(XSTS, envelope(xstsProperties, "rp://api.minecraftservices.com/").toString(), false, null);
        String hash;
        try { hash = required(xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject(), "uhs"); }
        catch (RuntimeException e) { throw malformed(); }
        JsonObject login = new JsonObject();
        login.addProperty("identityToken", "XBL3.0 x=" + hash + ";" + required(xsts, "Token"));
        String token = required(request(MINECRAFT, login.toString(), false, null), "access_token");
        JsonObject profile = request(PROFILE, null, false, token);
        UUID uuid;
        try {
            String id = required(profile, "id");
            if (!id.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException();
            uuid = UUID.fromString(id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16)
                    + "-" + id.substring(16, 20) + "-" + id.substring(20));
        } catch (RuntimeException e) { throw malformed(); }
        if (!uuid.equals(expected)) throw new AuthFailure(AuthFailure.Kind.ACCOUNT,
                "Wrong Minecraft account. Pair again with the Microsoft account for this instance.");
        return new User(required(profile, "name"), uuid, token, Optional.empty(), Optional.of(clientId));
    }

    private static JsonObject envelope(JsonObject properties, String relyingParty) {
        JsonObject request = new JsonObject();
        request.add("Properties", properties);
        request.addProperty("RelyingParty", relyingParty);
        request.addProperty("TokenType", "JWT");
        return request;
    }

    private JsonObject request(String endpoint, String body, boolean isForm, String bearer) throws AuthFailure, InterruptedException {
        return request(endpoint, body, isForm, bearer, false);
    }

    private JsonObject request(String endpoint, String body, boolean isForm, String bearer, boolean devicePoll) throws AuthFailure, InterruptedException {
        Response response;
        try { response = transport.send(endpoint, body, isForm, bearer); }
        catch (IOException e) { throw new AuthFailure(AuthFailure.Kind.RETRY, "Authentication network request failed; retrying in 60 seconds."); }
        if (response.status() == 408 || response.status() == 429 || response.status() >= 500)
            throw new AuthFailure(AuthFailure.Kind.RETRY, "Authentication service unavailable; retrying in 60 seconds.");
        JsonObject json;
        try { json = JsonParser.parseString(response.body()).getAsJsonObject(); }
        catch (RuntimeException e) {
            if (response.status() >= 400 && response.status() < 500)
                throw new AuthFailure(AuthFailure.Kind.ACCOUNT, "Authentication was refused. Check the account and pair again.");
            throw malformed();
        }
        if (response.status() < 200 || response.status() >= 300) {
            String error = json.has("error") && json.get("error").isJsonPrimitive() ? json.get("error").getAsString() : "";
            if (endpoint.equals(MINECRAFT) && response.status() == 403) {
                String detail = json.has("errorMessage") && json.get("errorMessage").isJsonPrimitive()
                        ? json.get("errorMessage").getAsString() : error;
                if (detail.equalsIgnoreCase("Invalid app registration"))
                    throw new AuthFailure(AuthFailure.Kind.LOCAL,
                            "Minecraft rejected this Microsoft application registration. Verify Minecraft API access before enabling HTTPS sign-in.");
            }
            if (devicePoll) {
                if (error.equals("authorization_pending")) throw new AuthFailure(AuthFailure.Kind.PENDING, "Awaiting phone sign-in.");
                if (error.equals("slow_down")) throw new AuthFailure(AuthFailure.Kind.SLOW_DOWN, "Waiting before the next sign-in check.");
                if (Set.of("authorization_declined", "access_denied", "expired_token", "bad_verification_code").contains(error))
                    throw new AuthFailure(AuthFailure.Kind.LOGIN, "Phone sign-in expired or was declined. Request a new code when ready.");
            }
            if (endpoint.equals(TOKEN) && Set.of("invalid_grant", "interaction_required", "login_required", "consent_required").contains(error))
                throw new AuthFailure(AuthFailure.Kind.LOGIN, "Microsoft requires another sign-in. Pair this instance again.");
            if ((endpoint.equals(TOKEN) || endpoint.equals(DEVICE)) && Set.of("invalid_client", "invalid_scope", "unauthorized_client", "unsupported_grant_type").contains(error))
                throw new AuthFailure(AuthFailure.Kind.LOCAL, "Microsoft rejected the OAuth client configuration. Check the configured application and redirect URI.");
            if (Set.of("temporarily_unavailable", "server_error").contains(error))
                throw new AuthFailure(AuthFailure.Kind.RETRY, "Authentication service unavailable; retrying in 60 seconds.");
            throw new AuthFailure(AuthFailure.Kind.ACCOUNT, "Authentication was refused. Check the account and pair again.");
        }
        return json;
    }

    private static String required(JsonObject json, String field) throws AuthFailure {
        try {
            String value = json.get(field).getAsString();
            if (value.isBlank()) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException e) { throw malformed(); }
    }

    private static AuthFailure malformed() { return new AuthFailure(AuthFailure.Kind.RETRY, "Incomplete authentication response; retrying in 60 seconds."); }

    static String form(Map<String, String> fields) {
        return fields.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }
}
