package com.minecart.display.render.snap;

/**
 * A box to draw with an optional Y-axis rotation (degrees). Used for the placement ghost, whose body bar
 * rotates smoothly to the snapped direction (its terminal bumps stay axis-aligned). {@code yawDeg} is the
 * heading the bar points in the XZ plane (0 = +X); the renderer converts it to the matching mesh rotation.
 */
public record OrientedBox(float cx, float cy, float cz,
                          float sizeX, float sizeY, float sizeZ,
                          float yawDeg, BoxSpec.Category category) {
}
