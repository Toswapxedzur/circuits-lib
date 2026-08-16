package com.minecart.snap;

import com.minecart.logic.ServerWorld;

/**
 * A kind of snap part — the snap-mode analogue of {@link com.minecart.registry.CircuitElementType}. It
 * carries a stable id, a pixel {@link #height()} (how many units tall the part stands on the board;
 * default {@value #DEFAULT_HEIGHT} per the mode's "component height = 4" rule), and a {@link Builder}
 * that emits the part's electrical contribution into the world during graph derivation.
 *
 * <p>Crucially, a snap part does <em>not</em> introduce a new electrical primitive: its builder wires the
 * existing core elements ({@code WIRE}, {@code RESISTOR}, {@code BATTERY}, …) between the shared post
 * nodes resolved from the board. That is what lets snap mode reuse the exact same solver as 2D mode — a
 * snap part is a placement+appearance wrapper over the same circuit graph.
 */
public final class SnapPartType {

    /** Default part height in board units, matching the snap-mode spec ("height of one component = 4"). */
    public static final int DEFAULT_HEIGHT = 4;

    /** Emits a placement's electrical elements into {@code world}, resolving pins through {@code posts}. */
    @FunctionalInterface
    public interface Builder {
        void build(SnapPlacement placement, ServerWorld world, PostGrid posts);
    }

    private final String id;
    private final int height;
    private final boolean connector;
    private final Builder builder;

    SnapPartType(String id, int height, boolean connector, Builder builder) {
        this.id = id;
        this.height = height;
        this.connector = connector;
        this.builder = builder;
    }

    /** Stable registry / on-disk id. */
    public String id() { return id; }

    /** Part height in board units. */
    public int height() { return height; }

    /**
     * Whether this part is an ideal connector (a wire) that merges its posts rather than adding a device
     * edge. Connectors are derived in a first pass so their {@link PostGrid#union unions} are in place
     * before any device resolves its nodes. See {@link SnapBoard#rebuild}.
     */
    public boolean isConnector() { return connector; }

    /** Derives this placement's electrical elements into {@code world}. */
    public void build(SnapPlacement placement, ServerWorld world, PostGrid posts) {
        builder.build(placement, world, posts);
    }
}
