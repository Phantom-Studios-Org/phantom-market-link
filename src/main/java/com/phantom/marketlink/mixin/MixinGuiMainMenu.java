package com.phantom.marketlink.mixin;

import com.phantom.marketlink.gui.LinkMenuButton;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.malilib.gui.GuiBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Button injected at the TAIL of GuiMainMenu.initGui(), after Litematica's own buttons.
// addButton is public on GuiBase — no shadow/accesswidener needed.
@Mixin(GuiMainMenu.class)
public abstract class MixinGuiMainMenu {
    @Inject(method = "initGui", at = @At("TAIL"))
    private void phantomMarketLink$addLinkButton(CallbackInfo ci) {
        LinkMenuButton.addTo((GuiBase) (Object) this);
    }
}
