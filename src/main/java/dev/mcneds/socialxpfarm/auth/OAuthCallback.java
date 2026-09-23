package dev.mcneds.socialxpfarm.auth;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Single-use authorization receiver: local browser listener or remote HTTPS callback, with per-attempt state and PKCE. */
final class OAuthCallback implements BrowserLogin {
    private final HttpServer server;
    private final String publicRedirect;
    private final CompletableFuture<String> code = new CompletableFuture<>();
    final String verifier = random();
    final String state = random();

    private final String clientId;
    private final java.util.function.LongSupplier clock;
    private final long started;
    private final long expiresAt = System.currentTimeMillis() + 300_000;

    OAuthCallback() throws IOException { this(MicrosoftAuthClient.CLIENT_ID, System::nanoTime); }
    OAuthCallback(String clientId) throws IOException { this(clientId, System::nanoTime); }
    OAuthCallback(String clientId, String redirect) throws IOException { this(clientId, redirect, System::nanoTime); }
    OAuthCallback(String clientId, java.util.function.LongSupplier clock) throws IOException { this(clientId, null, clock); }
    OAuthCallback(String clientId, String redirect, java.util.function.LongSupplier clock) throws IOException {
        UUID.fromString(clientId);
        this.clientId = clientId;
        this.clock = clock;
        started = clock.getAsLong();
        publicRedirect = validatePublicRedirect(redirect);
        if (publicRedirect != null) {
            server = null;
            return;
        }
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/callback", exchange -> {
            int status = 400;
            String message = "Invalid login callback. Return to Minecraft.";
            try {
                if (exchange.getRequestMethod().equals("GET")
                        && exchange.getRequestURI().getRawPath().equals("/callback")
                        && submit(redirect() + "?" + exchange.getRequestURI().getRawQuery())) {
                    status = 200;
                    message = "Sign-in received. Return to Minecraft or Discord to check the account.";
                }
            } catch (IllegalArgumentException ignored) { /* Malformed requests cannot consume a pending login. */ }
            byte[] body = message.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
    }

    String redirect() { return publicRedirect != null ? publicRedirect : "http://localhost:" + server.getAddress().getPort() + "/callback"; }

    URI authorizeUri() {
        Map<String, String> fields = new HashMap<>(Map.of(
                "client_id", clientId, "response_type", "code", "redirect_uri", redirect(),
                "scope", "XboxLive.signin offline_access", "state", state, "prompt", "select_account",
                "code_challenge", challenge(verifier), "code_challenge_method", "S256"));
        if (publicRedirect != null) fields.put("response_mode", "form_post");
        return URI.create(MicrosoftAuthClient.AUTHORIZE + "?" + MicrosoftAuthClient.form(fields));
    }

    @Override public BrowserPrompt prompt() {
        return code.isDone() || expired() ? null : new BrowserPrompt(authorizeUri().toString(), expiresAt);
    }

    private boolean expired() { return clock.getAsLong() - started >= 300_000_000_000L; }

    @Override public boolean submit(String address) {
        if (address == null || address.length() > 4000 || expired() || code.isDone()) return false;
        try {
            URI uri = URI.create(address.strip());
            if (uri.getRawFragment() != null || uri.getRawQuery() == null
                    || !address.strip().substring(0, address.strip().indexOf('?')).equals(redirect())) return false;
            Map<String, String> query = parseQuery(uri.getRawQuery());
            if (!state.equals(query.get("state")) || query.containsKey("code") == query.containsKey("error")) return false;
            if (query.containsKey("error")) return code.completeExceptionally(
                    new AuthFailure(AuthFailure.Kind.LOGIN, "Microsoft sign-in was cancelled or denied."));
            String value = query.get("code");
            return value != null && !value.isBlank() && code.complete(value);
        } catch (IllegalArgumentException e) { return false; }
    }

    String awaitCode() throws AuthFailure, InterruptedException {
        try { return code.get(Math.max(0, 300_000_000_000L - (clock.getAsLong() - started)), TimeUnit.NANOSECONDS); }
        catch (TimeoutException e) { throw new AuthFailure(AuthFailure.Kind.LOGIN, "Sign-in timed out. Try pairing again."); }
        catch (ExecutionException e) { throw new AuthFailure(AuthFailure.Kind.LOGIN, "Microsoft sign-in was cancelled or denied."); }
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw == null) return values;
        for (String entry : raw.split("&")) {
            String[] pair = entry.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate callback parameter");
        }
        return values;
    }

    static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private static String random() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String validatePublicRedirect(String value) {
        if (value == null) return null;
        if (value.length() > 256 || !value.matches("https://(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}/oauth/callback")
                || value.contains(".localhost/") || value.contains(".local/"))
            throw new IllegalArgumentException("Invalid public callback address");
        return value;
    }

    @Override public void close() { if (server != null) server.stop(0); code.cancel(false); }
}
