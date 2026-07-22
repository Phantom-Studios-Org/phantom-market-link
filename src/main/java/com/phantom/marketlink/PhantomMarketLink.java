package com.phantom.marketlink;

import com.phantom.marketlink.auth.DeviceAuthManager;
import com.phantom.marketlink.config.LinkConfig;
import com.phantom.marketlink.link.LinkClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.SharedConstants;
//? if mc26 {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
*///?} else {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
//?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

// Client entrypoint and central hub for PhantomMarket Link.
public final class PhantomMarketLink implements ClientModInitializer {

    public static final String MOD_ID = "phantom-market-link";
    public static final Logger LOGGER = LoggerFactory.getLogger("PhantomMarketLink");

    private static PhantomMarketLink instance;

    private LinkConfig config;
    private DeviceAuthManager authManager;
    private LinkClient linkClient;

    // stable for the game process lifetime
    private final String instanceId = UUID.randomUUID().toString();
    private String mcVersion = "unknown";

    private volatile LinkState state = LinkState.LOGGED_OUT;
    private volatile String detail = null;
    private volatile Runnable buttonRefresh = null;

    // re-click logout confirmation (a GUI dialog would be version-fragile)
    private volatile long logoutConfirmDeadline = 0L;
    private static final long LOGOUT_CONFIRM_WINDOW_MS = 4000L;

    public static PhantomMarketLink get() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        try {
            // 26.x: getCurrentVersion(); 1.21.11: getGameVersion().name(); older: .getName()
            //? if mc26 {
            /*this.mcVersion = SharedConstants.getCurrentVersion().name();
            *///?} elif mc12111 {
            /*this.mcVersion = SharedConstants.getGameVersion().name();
            *///?} else {
            this.mcVersion = SharedConstants.getGameVersion().getName();
            //?}
        } catch (Throwable t) {
            LOGGER.warn("Could not resolve MC version name", t);
        }

        this.config = LinkConfig.load();
        this.authManager = new DeviceAuthManager(config, this);
        this.linkClient = new LinkClient(config, this, instanceId, mcVersion);

        if (config.isLoggedIn()) {
            setState(LinkState.CONNECTING, null);
            linkClient.start();
        } else {
            setState(LinkState.LOGGED_OUT, null);
        }

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                linkClient.updatePresence(currentWorldName(client)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                linkClient.updatePresence(null));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            authManager.cancel();
            linkClient.shutdown();
        });

        LOGGER.info("PhantomMarket Link initialised (mc={}, instanceId={})", mcVersion, instanceId);
    }

    // ---- Button interaction (called from the GUI mixin) ----

    public void onButtonClicked() {
        switch (state) {
            case LOGGED_OUT -> authManager.start();
            case LOGGING_IN -> authManager.cancel();
            case CONNECTING, CONNECTED, RECONNECTING -> requestLogout();
        }
    }

    private void requestLogout() {
        long now = System.currentTimeMillis();
        if (now <= logoutConfirmDeadline) {
            logoutConfirmDeadline = 0L;
            logout();
        } else {
            logoutConfirmDeadline = now + LOGOUT_CONFIRM_WINDOW_MS;
            com.phantom.marketlink.gui.LinkMessages.info("phantom_market_link.confirm.logout.message");
        }
    }

    public void logout() {
        linkClient.shutdown();
        config.clearSession();
        setState(LinkState.LOGGED_OUT, null);
        com.phantom.marketlink.gui.LinkMessages.info("phantom_market_link.message.logged_out");
    }

    // ---- Callbacks from the auth manager ----

    public void onDeviceCode(String userCode) {
        setState(LinkState.LOGGING_IN, userCode);
    }

    public void onLoginSuccess(String sessionToken, String username) {
        config.setSession(sessionToken, username);
        setState(LinkState.CONNECTING, null);
        com.phantom.marketlink.gui.LinkMessages.info("phantom_market_link.message.login_success", username);
        linkClient.start();
    }

    public void onLoginError(String message) {
        setState(LinkState.LOGGED_OUT, null);
        com.phantom.marketlink.gui.LinkMessages.info("phantom_market_link.message.login_failed", message);
    }

    // ---- Callbacks from the link client ----

    public void onWsConnecting() {
        setState(LinkState.CONNECTING, null);
    }

    public void onWsConnected(String username) {
        setState(LinkState.CONNECTED, username != null ? username : config.phantomUsername());
    }

    public void onWsReconnecting() {
        setState(LinkState.RECONNECTING, config.phantomUsername());
    }

    public void onSessionRevoked() {
        config.clearSession();
        setState(LinkState.LOGGED_OUT, null);
        com.phantom.marketlink.gui.LinkMessages.info("phantom_market_link.message.session_expired");
    }

    // ---- State plumbing ----

    private void setState(LinkState newState, String newDetail) {
        this.state = newState;
        this.detail = newDetail;
        Runnable refresh = this.buttonRefresh;
        if (refresh != null) {
            //? if mc26 {
            /*Minecraft.getInstance().execute(refresh);
            *///?} else {
            MinecraftClient.getInstance().execute(refresh);
            //?}
        }
    }

    public LinkState state() {
        return state;
    }

    public String detail() {
        return detail;
    }

    public LinkConfig config() {
        return config;
    }

    public void setButtonRefresh(Runnable refresh) {
        this.buttonRefresh = refresh;
    }

    // ---- Helpers ----

    //? if mc26 {
    /*private static String currentWorldName(Minecraft client) {
        if (client == null) {
            return null;
        }
        if (client.isLocalServer() && client.getSingleplayerServer() != null) {
            try {
                return client.getSingleplayerServer().getWorldData().getLevelName();
            } catch (Throwable ignored) {
            }
        }
        ServerData info = client.getCurrentServer();
        if (info != null) {
            if (info.name != null && !info.name.isBlank()) {
                return info.name;
            }
            return info.ip;
        }
        return null;
    }
    *///?} else {
    private static String currentWorldName(MinecraftClient client) {
        if (client == null) {
            return null;
        }
        if (client.isInSingleplayer() && client.getServer() != null) {
            try {
                return client.getServer().getSaveProperties().getLevelName();
            } catch (Throwable ignored) {
            }
        }
        ServerInfo info = client.getCurrentServerEntry();
        if (info != null) {
            if (info.name != null && !info.name.isBlank()) {
                return info.name;
            }
            return info.address;
        }
        return null;
    }//?}
}
