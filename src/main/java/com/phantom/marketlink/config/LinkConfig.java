package com.phantom.marketlink.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.phantom.marketlink.PhantomMarketLink;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// JSON config at <configDir>/phantom-market-link.json.
// sessionToken is a bearer session token — never log it.
public final class LinkConfig {

    public static final String DEFAULT_BASE_URL = "https://market.phantom-node.com";
    public static final String DEFAULT_GATEWAY_URL = "wss://market.phantom-node.com/link/connect";

    private static final String FILE_NAME = "phantom-market-link.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private String baseUrl = DEFAULT_BASE_URL;
    private String gatewayUrl = DEFAULT_GATEWAY_URL;
    private String sessionToken = null;
    private String phantomUsername = null;

    private transient Path path;

    public static LinkConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        LinkConfig config = new LinkConfig();
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                LinkConfig parsed = GSON.fromJson(json, LinkConfig.class);
                if (parsed != null) {
                    config = parsed;
                }
            } catch (IOException | JsonSyntaxException e) {
                // Never include the token in logs; the message from a config parse never contains it.
                PhantomMarketLink.LOGGER.warn("Failed to read config, using defaults", e);
            }
        }
        config.path = path;
        config.applyDefaults();
        config.save();
        return config;
    }

    private void applyDefaults() {
        // The session token rides on these URLs, so refuse cleartext to remote
        // hosts: require https/wss, allowing http/ws only for localhost (dev).
        if (!isSecureOrLocal(baseUrl, "https", "http")) {
            baseUrl = DEFAULT_BASE_URL;
        }
        if (!isSecureOrLocal(gatewayUrl, "wss", "ws")) {
            gatewayUrl = DEFAULT_GATEWAY_URL;
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

    private static boolean isSecureOrLocal(String url, String secureScheme, String plainScheme) {
        if (url == null || url.isBlank()) {
            return false;
        }
        if (url.startsWith(secureScheme + "://")) {
            return true;
        }
        if (url.startsWith(plainScheme + "://")) {
            try {
                String host = java.net.URI.create(url).getHost();
                return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
            } catch (RuntimeException e) {
                return false;
            }
        }
        return false;
    }

    public synchronized void save() {
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            PhantomMarketLink.LOGGER.warn("Failed to write config", e);
        }
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String gatewayUrl() {
        return gatewayUrl;
    }

    public String sessionToken() {
        return sessionToken;
    }

    public String phantomUsername() {
        return phantomUsername;
    }

    public boolean isLoggedIn() {
        return sessionToken != null && !sessionToken.isBlank();
    }

    /** Persist a successful login. */
    public synchronized void setSession(String sessionToken, String phantomUsername) {
        this.sessionToken = sessionToken;
        this.phantomUsername = phantomUsername;
        save();
    }

    /** Clear credentials (logout or revoked session). */
    public synchronized void clearSession() {
        this.sessionToken = null;
        this.phantomUsername = null;
        save();
    }
}
