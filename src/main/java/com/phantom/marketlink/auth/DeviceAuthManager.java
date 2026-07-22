package com.phantom.marketlink.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.phantom.marketlink.PhantomMarketLink;
import com.phantom.marketlink.config.LinkConfig;
import com.phantom.marketlink.gui.LinkMessages;
import net.minecraft.util.Util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

// BetterAuth device authorization flow (RFC 8628) — runs on a background daemon thread.
public final class DeviceAuthManager {

    private static final String CLIENT_ID = "phantom-market-link";
    private static final long DEFAULT_INTERVAL_SEC = 5L;
    private static final long DEFAULT_EXPIRES_SEC = 600L;

    private final LinkConfig config;
    private final PhantomMarketLink hub;
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile Thread worker;

    public DeviceAuthManager(LinkConfig config, PhantomMarketLink hub) {
        this.config = config;
        this.hub = hub;
    }

    public synchronized void start() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        Thread t = new Thread(this::runFlow, "phantom-market-link-auth");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    public synchronized void cancel() {
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
        worker = null;
    }

    private void runFlow() {
        try {
            JsonObject codeResp = requestDeviceCode();
            String deviceCode = codeResp.get("device_code").getAsString();
            String userCode = codeResp.get("user_code").getAsString();
            String verificationUri = codeResp.has("verification_uri_complete")
                    ? codeResp.get("verification_uri_complete").getAsString()
                    : codeResp.get("verification_uri").getAsString();
            long interval = codeResp.has("interval") ? codeResp.get("interval").getAsLong() : DEFAULT_INTERVAL_SEC;
            long expiresIn = codeResp.has("expires_in") ? codeResp.get("expires_in").getAsLong() : DEFAULT_EXPIRES_SEC;
            long deadline = System.currentTimeMillis() + expiresIn * 1000L;

            hub.onDeviceCode(userCode);
            LinkMessages.info("phantom_market_link.message.login_started", userCode);
            openBrowser(verificationUri);
            // user_code is the public code shown to the user; never log device_code (the secret)
            PhantomMarketLink.LOGGER.info(
                    "Device flow started (user_code={}); polling token every {}s until approval or {}s deadline",
                    userCode, interval, expiresIn);

            while (!Thread.currentThread().isInterrupted() && System.currentTimeMillis() < deadline) {
                Thread.sleep(Math.max(1L, interval) * 1000L);

                JsonObject tokenResp;
                try {
                    tokenResp = pollToken(deviceCode);
                } catch (InterruptedException interrupted) {
                    throw interrupted; // user cancel — handled by the outer catch
                } catch (Exception transientError) {
                    // RFC 8628: keep polling through transient failures (dropped conn, CF cold start, 5xx)
                    PhantomMarketLink.LOGGER.debug("Transient poll failure, retrying", transientError);
                    continue;
                }

                if (tokenResp.has("access_token")) {
                    String token = tokenResp.get("access_token").getAsString();
                    String username = fetchUsername(token);
                    // never log the token
                    PhantomMarketLink.LOGGER.info("Device login succeeded for user '{}'", username);
                    hub.onLoginSuccess(token, username);
                    return;
                }
                String error = tokenResp.has("error") ? tokenResp.get("error").getAsString() : "unknown";
                PhantomMarketLink.LOGGER.debug("Device token poll → {}", error);
                switch (error) {
                    case "slow_down" -> interval += 5;
                    // only the RFC 8628 terminal errors abort; everything else is retriable
                    case "access_denied", "expired_token", "invalid_grant", "invalid_client" -> {
                        hub.onLoginError(error);
                        return;
                    }
                    default -> { /* pending / rate-limit / transient — keep polling */ }
                }
            }
            if (!Thread.currentThread().isInterrupted()) {
                hub.onLoginError("expired_token");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // user cancel; state already reset in cancel()
        } catch (Exception e) {
            PhantomMarketLink.LOGGER.error("DeviceAuthManager.runFlow failed", e);
            hub.onLoginError("network_error");
        } finally {
            worker = null;
        }
    }

    private JsonObject requestDeviceCode() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("client_id", CLIENT_ID);
        return postJson(config.baseUrl() + "/api/auth/device/code", body.toString(), null);
    }

    private JsonObject pollToken(String deviceCode) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        body.addProperty("device_code", deviceCode);
        body.addProperty("client_id", CLIENT_ID);
        return postJson(config.baseUrl() + "/api/auth/device/token", body.toString(), null);
    }

    private String fetchUsername(String sessionToken) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/auth/get-session"))
                    .header("Authorization", "Bearer " + sessionToken)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject json = gson.fromJson(resp.body(), JsonObject.class);
            if (json != null) {
                JsonObject user = json.has("user") && json.get("user").isJsonObject()
                        ? json.getAsJsonObject("user")
                        : json;
                if (user.has("name") && !user.get("name").isJsonNull()) {
                    return user.get("name").getAsString();
                }
            }
        } catch (Exception e) {
            PhantomMarketLink.LOGGER.warn("Could not fetch PhantomMarket username", e);
        }
        return "PhantomMarket";
    }

    private JsonObject postJson(String url, String jsonBody, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonObject json = gson.fromJson(resp.body(), JsonObject.class);
        if (json == null) {
            throw new IllegalStateException("Empty response from " + url + " (HTTP " + resp.statusCode() + ")");
        }
        return json;
    }

    private void openBrowser(String url) {
        // only open the URL if it's our own host over https (or localhost http) — a
        // malicious server could otherwise hand us a file:// or phishing page
        try {
            URI uri = URI.create(url);
            URI base = URI.create(config.baseUrl());
            boolean sameHost = base.getHost() != null && base.getHost().equalsIgnoreCase(uri.getHost());
            boolean localhost = "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
            boolean okScheme = "https".equalsIgnoreCase(uri.getScheme())
                    || ("http".equalsIgnoreCase(uri.getScheme()) && localhost);
            if (!okScheme || !sameHost) {
                PhantomMarketLink.LOGGER.warn("Refusing to open unexpected verification URL: {}", url);
                return;
            }
            Util.getOperatingSystem().open(uri);
        } catch (Throwable t) {
            PhantomMarketLink.LOGGER.warn("Could not open browser; visit the URL manually", t);
        }
    }
}
