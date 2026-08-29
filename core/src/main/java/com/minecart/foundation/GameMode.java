package com.minecart.foundation;

/**
 * The building paradigm a save ({@link Level}) was created with. Chosen once at world creation and
 * <em>fixed</em> for the life of the save — every {@link World} in the level shares it, and no gameplay
 * path mutates it after creation. The value is persisted in {@code level.dat} (see
 * {@code WorldStorage}) and drives which editor the client opens (a 2D free-wiring screen vs. a 3D
 * snap-circuit screen) as well as which placement/connection rules the server applies.
 *
 * <p>The underlying electrical model and solver are identical across modes; only the spatial
 * representation, interaction, and rendering differ. See {@link #FLAT_2D} and {@link #SNAP_3D}.
 */
public enum GameMode {

    /**
     * The original free-form 2D board: elements carry continuous {@code PositionInfo} coordinates, wires
     * are placed freely between nodes, and the flexible/rigid physics solver drives drag. This is the
     * default for any save that predates the mode field.
     */
    FLAT_2D("flat_2d", "2D"),

    /**
     * 3D snap-circuit board: the user is given an extensible baseboard of discrete slots (one slot spans
     * a 16×16 pixel footprint, components stand 4 units tall) and snaps pixelated parts onto it. Parts
     * connect through shared grid posts rather than free wires; the electrical graph is derived from
     * board geometry. Rendered with a perspective camera.
     */
    SNAP_3D("snap_3d", "3D Snap"),

    /**
     * A client-side <b>debug</b> mode: instead of an editable board, the screen lays out EVERY committed part
     * model in a square grid (the merged in-game "texture displayer"). It creates an empty save like the 2D
     * default (no board is seeded) and needs no server — joining it opens the model gallery directly. For
     * eyeballing the whole part catalogue at a glance; not a building paradigm.
     */
    DEBUG_MODELS("debug_models", "Debug");

    private final String id;
    private final String displayName;

    GameMode(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** Stable on-disk / wire identifier. Never change these strings — doing so invalidates saves. */
    public String id() {
        return id;
    }

    /** Short human-readable label for menus and world-list badges. */
    public String displayName() {
        return displayName;
    }

    /**
     * Resolves a persisted {@link #id()} back to a mode. Unknown or {@code null} ids fall back to
     * {@link #FLAT_2D} so that a save missing the field (or written by a newer build) still loads as the
     * safe default rather than failing.
     */
    public static GameMode fromId(String id) {
        if (id != null) {
            for (GameMode mode : values()) {
                if (mode.id.equals(id)) {
                    return mode;
                }
            }
        }
        return FLAT_2D;
    }
}
