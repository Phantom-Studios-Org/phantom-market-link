package com.phantom.marketlink.gui;

import com.phantom.marketlink.LinkState;
import com.phantom.marketlink.PhantomMarketLink;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;

// PhantomMarket button on Litematica's main menu (bottom-right).
public final class LinkMenuButton {

    private LinkMenuButton() {
    }

    private static final int WIDTH = 160;
    private static final int HEIGHT = 20;
    private static final int MARGIN = 12;

    public static void addTo(GuiBase gui) {
        // width/height: inherited Screen fields, present on all versions
        int x = gui.width - WIDTH - MARGIN;
        int y = gui.height - HEIGHT - 6;

        ButtonGeneric button = new ButtonGeneric(x, y, WIDTH, HEIGHT, label());
        gui.addButton(button, (b, mouseButton) -> {
            PhantomMarketLink hub = PhantomMarketLink.get();
            if (hub != null) {
                hub.onButtonClicked();
            }
        });

        // live label refresh: background threads call this on the client thread on state change
        PhantomMarketLink hub = PhantomMarketLink.get();
        if (hub != null) {
            hub.setButtonRefresh(() -> {
                try {
                    button.setDisplayString(label());
                } catch (Throwable ignored) {
                    // button may be orphaned once the screen closed
                }
            });
        }
    }

    public static String label() {
        PhantomMarketLink hub = PhantomMarketLink.get();
        LinkState state = hub != null ? hub.state() : LinkState.LOGGED_OUT;
        String detail = hub != null ? hub.detail() : null;
        return switch (state) {
            case LOGGED_OUT -> tr("phantom_market_link.button.login");
            case LOGGING_IN -> detail != null
                    ? tr("phantom_market_link.button.code", detail)
                    : tr("phantom_market_link.button.logging_in");
            case CONNECTING -> tr("phantom_market_link.button.connecting");
            case CONNECTED -> tr("phantom_market_link.button.connected", detail != null ? detail : "");
            case RECONNECTING -> tr("phantom_market_link.button.reconnecting");
        };
    }

    private static String tr(String key, Object... args) {
        return StringUtils.translate(key, args);
    }
}
