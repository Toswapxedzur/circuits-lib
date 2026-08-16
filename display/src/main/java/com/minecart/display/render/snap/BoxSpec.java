package com.minecart.display.render.snap;

/**
 * A single axis-aligned box to draw in the 3D snap scene, in world units, decoupled from libGDX so the
 * board→geometry mapping can be unit-tested without a GL context. {@link SnapSceneGeometry} produces these
 * from a {@link com.minecart.snap.SnapBoard}; {@code SnapRenderer} turns each into a colored
 * {@code ModelInstance}. {@code center} is the box centre; {@code sizeX/Y/Z} are full extents (Y is up).
 */
public record BoxSpec(float cx, float cy, float cz, float sizeX, float sizeY, float sizeZ, Category category) {

    /** What the box represents, mapped to a colour/material by the renderer. */
    public enum Category {
        BASE,
        BUMP,
        WIRE,
        RESISTOR,
        BATTERY,
        UNKNOWN
    }
}
