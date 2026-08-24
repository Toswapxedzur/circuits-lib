package com.minecart.snap3d;

/**
 * A named backdrop preset — the macro layout of a scene (terrain shape, water line, vegetation density).
 * The first one, {@link #LAKE_RING}, is a central island in a lake, ringed by gentle plains, encircled by
 * dramatic mountains. More presets are just more constants here (desert mesa, fjord, volcano, …).
 *
 * <p>All radii are measured from ({@code centerX},{@code centerZ}) in world units; heights are world Y.
 */
public record ScenePreset(
        String name,
        float centerX, float centerZ, float waterLine,
        // concentric radii: island | lake (out to lakeOuter) | plains (to plainsOuter) | mountains (to mountOuter)
        float islandR, float lakeOuter, float plainsOuter, float mountOuter,
        // zone heights (world Y)
        float islandHeight, float lakeDepth, float plainsHeight, float mountHeight,
        // vegetation
        int treeCount, int propCount, float treeMaxAltitude) {

    /** Island in a lake, gentle plains, encircling dramatic mountains. */
    public static final ScenePreset LAKE_RING = new ScenePreset(
            "Lake Ring",
            0f, 60f, 10f,
            260f, 820f, 1400f, 2100f,
            38f, 70f, 50f, 720f,
            1500, 1000, 260f);
}
