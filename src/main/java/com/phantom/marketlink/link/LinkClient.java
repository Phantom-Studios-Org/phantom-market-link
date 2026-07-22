package com.phantom.marketlink.link;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.phantom.marketlink.PhantomMarketLink;
import com.phantom.marketlink.config.LinkConfig;
//? if mc26 {
import net.minecraft.client.Minecraft;
//?} else {
/*import net.minecraft.client.MinecraftClient;
*///?}

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// Persistent WebSocket link to PhantomMarket (/link/connect on the marketplace worker).
public final class LinkClient {

    private static final long BACKOFF_MIN_MS = 1000L;
    private static final long BACKOFF_MAX_MS = 60_000L;
    private static final int MAX_WORLD_NAME_LEN = 64;
    private static final int MAX_FRAME_CHARS = 64 * 1024;

    private final LinkConfig config;
    private final PhantomMarketLink hub;
    private final String instanceId;
    private final String mcVersion;
    private final Gson gson = new Gson();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "phantom-market-link-ws"));
    private final ScheduledExecutorService downloads =
            Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "phantom-market-link-dl"));

    private final SchematicReceiver receiver;

    private volatile boolean shutdown = false;
    private volatile WebSocket webSocket;
    private volatile String currentWorldName;
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);
    private final StringBuilder textBuffer = new StringBuilder();

    private long backoffMs = BACKOFF_MIN_MS;
    private int consecutive401 = 0;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    public LinkClient(LinkConfig config, PhantomMarketLink hub, String instanceId, String mcVersion) {
        this.config = config;
        this.hub = hub;
        this.instanceId = instanceId;
        this.mcVersion = mcVersion;
        this.receiver = new SchematicReceiver(config);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    public void start() {
        shutdown = false;
        hub.onWsConnecting();
        scheduler.execute(this::connect);
    }

    public void shutdown() {
        shutdown = true;
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "client shutdown");
            } catch (Exception ignored) {
            }
            ws.abort();
        }
    }

    public void updatePresence(String worldName) {
        this.currentWorldName = truncate(worldName);
        sendPresence();
    }

    // ---- Connection ----

    private void connect() {
        if (shutdown || !config.isLoggedIn()) {
            return;
        }
        reconnectPending.set(false);

        Boolean sessionOk;
        try {
            sessionOk = validateSession();
        } catch (SessionRevokedException revoked) {
            return; // already dropped to logged-out
        }
        if (sessionOk == null || !sessionOk) {
            scheduleReconnect();
            return;
        }

        try {
            String url = buildWsUrl();
            PhantomMarketLink.LOGGER.info("Connecting WebSocket to {}", config.gatewayUrl());
            http.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + config.sessionToken())
                    .connectTimeout(Duration.ofSeconds(20))
                    .buildAsync(URI.create(url), new SocketListener())
                    .whenComplete((ws, err) -> {
                        if (err != null || ws == null) {
                            PhantomMarketLink.LOGGER.warn("WebSocket connect failed: {}",
                                    err != null ? err.getMessage() : "null socket");
                            scheduleReconnect();
                        } else {
                            webSocket = ws;
                        }
                    });
        } catch (Exception e) {
            PhantomMarketLink.LOGGER.warn("WebSocket connect error", e);
            scheduleReconnect();
        }
    }

    // Preflight the token against get-session. BetterAuth answers an invalid/expired
    // bearer with 200 + null body (not 401), so "authed" means a JSON object with a user.
    // Returns true=valid, false=auth failed, null=transient; throws on the 2nd strike (logout).
    private Boolean validateSession() throws SessionRevokedException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/auth/get-session"))
                    .header("Authorization", "Bearer " + config.sessionToken())
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            boolean authFailed;
            if (status == 401) {
                authFailed = true;
            } else if (status / 100 == 2) {
                JsonObject json = gson.fromJson(resp.body(), JsonObject.class);
                authFailed = json == null || !json.has("user") || json.get("user").isJsonNull();
            } else {
                return null; // transient (5xx, rate limit, …)
            }
            if (authFailed) {
                consecutive401++;
                if (consecutive401 >= 2) {
                    PhantomMarketLink.LOGGER.info("Session revoked (two consecutive auth failures)");
                    shutdown = true;
                    hub.onSessionRevoked();
                    throw new SessionRevokedException();
                }
                return Boolean.FALSE;
            }
            consecutive401 = 0;
            return Boolean.TRUE;
        } catch (SessionRevokedException e) {
            throw e;
        } catch (Exception e) {
            PhantomMarketLink.LOGGER.warn("Failed to validate session", e);
            return null;
        }
    }

    private String buildWsUrl() {
        String mcUsername = "player";
        try {
            //? if mc26 {
            mcUsername = Minecraft.getInstance().getUser().getName();
            //?} else {
            /*mcUsername = MinecraftClient.getInstance().getSession().getUsername();
            *///?}
        } catch (Throwable ignored) {
        }
        return config.gatewayUrl()
                + "?instanceId=" + enc(instanceId)
                + "&mcVersion=" + enc(mcVersion)
                + "&loader=fabric"
                + "&mcUsername=" + enc(mcUsername);
    }

    private void scheduleReconnect() {
        if (shutdown || !config.isLoggedIn()) {
            return;
        }
        if (!reconnectPending.compareAndSet(false, true)) {
            return;
        }
        webSocket = null;
        hub.onWsReconnecting();

        long base = backoffMs;
        double jitterFactor = 1.0 + (ThreadLocalRandom.current().nextDouble() * 0.4 - 0.2); // ±20%
        long delay = Math.max(250L, (long) (base * jitterFactor));
        backoffMs = Math.min(backoffMs * 2, BACKOFF_MAX_MS);

        scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    // ---- Messages ----

    private void handleMessage(String raw) {
        JsonObject json;
        try {
            json = gson.fromJson(raw, JsonObject.class);
        } catch (Exception e) {
            PhantomMarketLink.LOGGER.warn("Ignoring malformed gateway frame", e);
            return;
        }
        if (json == null || !json.has("type")) {
            return;
        }
        String type = json.get("type").getAsString();
        switch (type) {
            case "connected" -> onConnected(json);
            case "send" -> onSend(json);
            case "pong" -> { /* keep-alive reply */ }
            default -> PhantomMarketLink.LOGGER.debug("Unhandled gateway frame type '{}'", type);
        }
    }

    private void onConnected(JsonObject json) {
        backoffMs = BACKOFF_MIN_MS;
        consecutive401 = 0;
        String username = json.has("phantomUsername") && !json.get("phantomUsername").isJsonNull()
                ? json.get("phantomUsername").getAsString()
                : config.phantomUsername();
        PhantomMarketLink.LOGGER.info("Gateway link established as '{}'", username);
        if (username != null && !username.equals(config.phantomUsername())) {
            config.setSession(config.sessionToken(), username);
        }
        hub.onWsConnected(username);
        sendPresence();
    }

    private void onSend(JsonObject json) {
        final String commandId = json.has("commandId") ? json.get("commandId").getAsString() : null;
        final String slug = json.has("slug") ? json.get("slug").getAsString() : null;
        final String fileHash = json.has("fileHash") ? json.get("fileHash").getAsString() : null;
        final String format = json.has("format") && !json.get("format").isJsonNull()
                ? json.get("format").getAsString()
                : null;

        downloads.execute(() -> {
            SchematicReceiver.Result result = receiver.receive(slug, fileHash, format);
            if (commandId != null) {
                sendAck(commandId, result);
            }
        });
    }

    private void sendAck(String commandId, SchematicReceiver.Result result) {
        JsonObject ack = new JsonObject();
        ack.addProperty("type", "ack");
        ack.addProperty("commandId", commandId);
        ack.addProperty("status", result.delivered() ? "delivered" : "failed");
        if (!result.delivered() && result.error() != null) {
            ack.addProperty("error", truncate(result.error(), 200));
        }
        send(ack.toString());
    }

    private void sendPresence() {
        WebSocket ws = webSocket;
        if (ws == null) {
            return;
        }
        JsonObject presence = new JsonObject();
        presence.addProperty("type", "presence");
        if (currentWorldName == null) {
            presence.add("worldName", com.google.gson.JsonNull.INSTANCE);
        } else {
            presence.addProperty("worldName", currentWorldName);
        }
        send(presence.toString());
    }

    private synchronized void send(String text) {
        WebSocket ws = webSocket;
        if (ws == null) {
            return;
        }
        // WebSocket forbids overlapping sends; chain them.
        sendChain = sendChain
                .handle((r, e) -> null)
                .thenCompose(ignored -> ws.sendText(text, true))
                .exceptionally(e -> {
                    PhantomMarketLink.LOGGER.warn("Failed to send frame", e);
                    return null;
                });
    }

    private final class SocketListener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            // field must be live before the first "connected" frame — presence send needs a socket
            LinkClient.this.webSocket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            // bound the reassembly buffer; endless last=false fragments must not OOM us
            if (textBuffer.length() > MAX_FRAME_CHARS) {
                textBuffer.setLength(0);
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "frame too large");
                return null;
            }
            if (last) {
                String message = textBuffer.toString();
                textBuffer.setLength(0);
                try {
                    handleMessage(message);
                } catch (Exception e) {
                    PhantomMarketLink.LOGGER.warn("Error handling gateway frame", e);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            PhantomMarketLink.LOGGER.info("WebSocket closed ({} {})", statusCode, reason);
            if (!shutdown) {
                scheduleReconnect();
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            PhantomMarketLink.LOGGER.warn("WebSocket error: {}", error.getMessage());
            if (!shutdown) {
                scheduleReconnect();
            }
        }
    }

    // ---- Utils ----

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        return truncate(s, MAX_WORLD_NAME_LEN);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static final class SessionRevokedException extends Exception {
    }
}
