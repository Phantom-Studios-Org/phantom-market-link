package com.phantom.marketlink.gui;

import com.phantom.marketlink.PhantomMarketLink;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;
//? if mc26 {
import net.minecraft.client.Minecraft;
//?} else {
/*import net.minecraft.client.MinecraftClient;
*///?}

// MaLiLib message wrapper; marshals onto the client thread (store is read on the render thread).
public final class LinkMessages {

    private LinkMessages() {
    }

    public static void info(String translationKey, Object... args) {
        show(MessageType.INFO, translationKey, args);
    }

    public static void success(String translationKey, Object... args) {
        show(MessageType.SUCCESS, translationKey, args);
    }

    private static void show(MessageType type, String translationKey, Object... args) {
        //? if mc26 {
        Minecraft mc = Minecraft.getInstance();
        //?} else {
        /*MinecraftClient mc = MinecraftClient.getInstance();
        *///?}
        if (mc == null) {
            return;
        }
        mc.execute(() -> {
            try {
                InfoUtils.showGuiOrInGameMessage(type, translationKey, args);
            } catch (Throwable t) {
                PhantomMarketLink.LOGGER.warn("Could not display message '{}'", translationKey, t);
            }
        });
    }
}
