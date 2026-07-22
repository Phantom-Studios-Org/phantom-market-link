package com.phantom.marketlink.gui;

import com.phantom.marketlink.PhantomMarketLink;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
//? if mc26 {
/*import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
*///?} else {
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
//?}

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * All direct Litematica API access lives here so version drift stays confined to one file.
 * Mirrors Litematica's own {@code GuiSchematicLoad} "create placement" path.
 */
public final class LitematicaBridge {

    private LitematicaBridge() {
    }

    public enum LoadResult {
        /** Placement created in the current world. */
        LOADED,
        /** File saved, but not loaded (e.g. player not in a world). Not an error. */
        SKIPPED,
        /** Save succeeded but the auto-load itself failed. */
        FAILED
    }

    /** Litematica's schematics base directory, normalized to {@code java.io.File}.
     *  Litematica returns a {@code Path} from 1.21.11 (Litematica 0.26+) and a
     *  {@code File} before that; we convert so the rest of the mod stays on File. */
    public static File getSchematicsDirectory() {
        try {
            //? if mc12111 {
            /*return DataManager.getSchematicsBaseDirectory().toFile();
            *///?} else {
            return DataManager.getSchematicsBaseDirectory();
            //?}
        } catch (Throwable t) {
            PhantomMarketLink.LOGGER.warn("Could not resolve Litematica schematics directory", t);
            return null;
        }
    }

    /**
     * Load a {@code .litematic} as a placement. Runs on the client thread and blocks the
     * caller (a download thread) briefly for the result.
     */
    public static LoadResult tryLoadPlacement(File file) {
        //? if mc26 {
        /*Minecraft mc = Minecraft.getInstance();
        *///?} else {
        MinecraftClient mc = MinecraftClient.getInstance();
        //?}
        if (mc == null) {
            return LoadResult.SKIPPED;
        }
        CompletableFuture<LoadResult> future = new CompletableFuture<>();
        mc.execute(() -> future.complete(loadOnClientThread(mc, file)));
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (Throwable t) {
            PhantomMarketLink.LOGGER.warn("Auto-load did not complete for {}", file.getName(), t);
            return LoadResult.FAILED;
        }
    }

    //? if mc26 {
    /*private static LoadResult loadOnClientThread(Minecraft mc, File file) {
    *///?} else {
    private static LoadResult loadOnClientThread(MinecraftClient mc, File file) {
    //?}
        try {
            //? if mc26 {
            /*if (mc.player == null || mc.level == null) {
            *///?} else {
            if (mc.player == null || mc.world == null) {
            //?}
                return LoadResult.SKIPPED; // not in a world — saving is enough
            }
            File dir = file.getParentFile();
            // createFromFile appends the .litematic extension, so pass the bare name.
            String name = stripExtension(file.getName());
            // createFromFile takes a Path from 1.21.11 (Litematica 0.26+), a File before.
            //? if mc12111 {
            /*LitematicaSchematic schematic = LitematicaSchematic.createFromFile(dir.toPath(), name);
            *///?} else {
            LitematicaSchematic schematic = LitematicaSchematic.createFromFile(dir, name);
            //?}
            if (schematic == null) {
                return LoadResult.FAILED;
            }
            // 26.x: Mojang position()/containing; 1.21.11: getEntityPos(); older: getPos().
            //? if mc26 {
            /*BlockPos origin = BlockPos.containing(mc.player.position());
            *///?} elif mc12111 {
            /*BlockPos origin = BlockPos.ofFloored(mc.player.getEntityPos());
            *///?} else {
            BlockPos origin = BlockPos.ofFloored(mc.player.getPos());
            //?}
            String placementName = name;
            if (schematic.getMetadata() != null && schematic.getMetadata().getName() != null) {
                placementName = schematic.getMetadata().getName();
            }
            SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
            SchematicPlacement placement = SchematicPlacement.createFor(schematic, origin, placementName, true, true);
            manager.addSchematicPlacement(placement, true);
            manager.setSelectedSchematicPlacement(placement);
            return LoadResult.LOADED;
        } catch (Throwable t) {
            PhantomMarketLink.LOGGER.warn("Auto-load failed for {}", file.getName(), t);
            return LoadResult.FAILED;
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
