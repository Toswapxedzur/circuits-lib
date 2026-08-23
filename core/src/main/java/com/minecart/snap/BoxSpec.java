package com.minecart.snap;

/**
 * A single axis-aligned box to draw in the 3D snap scene, in world units, decoupled from any renderer so the
 * board→geometry mapping can be unit-tested and shared across engines (libGDX + jMonkeyEngine).
 * {@link SnapSceneGeometry} produces these from a {@link SnapBoard}; a renderer turns each into a colored
 * mesh. {@code cx/cy/cz} is the box centre; {@code sizeX/Y/Z} are full extents (Y is up).
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
