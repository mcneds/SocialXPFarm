package dev.mcneds.socialxpfarm.auth;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Per-login loopback listener, with independent ports, state and PKCE for concurrent alt instances. */
final class OAuthCallback implements AutoCloseable {
    private final HttpServer server;
    private final CompletableFuture<String> code = new CompletableFuture<>();
    final String verifier = random();
    final String state = random();

    OAuthCallback() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/callback", exchange -> {
            int status = 400;
            String message = "Invalid login callback. Return to Minecraft.";
            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                if (exchange.getRequestMethod().equals("GET") && exchange.getRequestURI().getPath().equals("/callback")
                        && state.equals(query.get("state"))) {
                    if (query.containsKey("error")) {
                        code.completeExceptionally(new AuthFailure(AuthFailure.Kind.LOGIN, "Microsoft sign-in was cancelled or denied."));
                    } else if (query.containsKey("code") && !query.get("code").isBlank()) {
                        code.complete(query.get("code"));
                        status = 200;
                        message = "Sign-in received. Return to Minecraft to check the account.";
                    }
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

    String redirect() { return "http://localhost:" + server.getAddress().getPort() + "/callback"; }

    URI authorizeUri() {
        return URI.create(MicrosoftAuthClient.AUTHORIZE + "?" + MicrosoftAuthClient.form(Map.of(
                "client_id", MicrosoftAuthClient.CLIENT_ID, "response_type", "code", "redirect_uri", redirect(),
                "scope", "XboxLive.signin offline_access", "state", state, "prompt", "select_account",
                "code_challenge", challenge(verifier), "code_challenge_method", "S256")));
    }

    String awaitCode() throws AuthFailure, InterruptedException {
        try { return code.get(5, TimeUnit.MINUTES); }
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

    @Override public void close() { server.stop(0); code.cancel(false); }
}
