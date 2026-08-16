package com.minecart.snap;

import com.minecart.foundation.Circuit;
import com.minecart.logic.ServerWorld;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The snap-circuit baseboard: a discrete grid of slots the player snaps parts onto, plus the rule that
 * turns board geometry into an electrical graph the existing solver can run.
 *
 * <h2>Coordinates</h2>
 * The board is {@code width × height} slots on {@code layers} stacking levels. Parts attach at
 * {@link Post}s — the integer lattice points {@code (col,row)} with {@code 0 ≤ col ≤ width} and
 * {@code 0 ≤ row ≤ height}, on a layer in {@code [0, layers)}. The board is extensible: {@link #resize}
 * grows it without disturbing existing parts.
 *
 * <h2>Graph derivation ("shares the same basic logic")</h2>
 * The board holds no electrical state of its own. {@link #rebuild(ServerWorld)} regenerates the world's
 * circuit from scratch: every distinct post becomes one shared {@link com.minecart.logic.CircuitNode},
 * and every placed part contributes its core element(s) between its post nodes (see {@link SnapPartType}).
 * Parts that share a post are therefore electrically joined — snapping two parts together wires them —
 * and the resulting graph is solved by the same engine 2D worlds use. Call {@code rebuild} after any
 * placement change, then tick the world as usual.
 */
public final class SnapBoard {

    private int width;
    private int height;
    private final int layers;

    // Keyed by occupied edge so a second part can't be placed on the same two posts.
    private final Map<SnapPlacement.EdgeKey, SnapPlacement> placements = new LinkedHashMap<>();

    public SnapBoard(int width, int height, int layers) {
        if (width < 1 || height < 1 || layers < 1) {
            throw new IllegalArgumentException("board dimensions must be >= 1");
        }
        this.width = width;
        this.height = height;
        this.layers = layers;
    }

    public int width() { return width; }
    public int height() { return height; }
    public int layers() { return layers; }

    /** All placed parts, in insertion order. */
    public Collection<SnapPlacement> placements() {
        return Collections.unmodifiableCollection(placements.values());
    }

    /** Whether {@code post} lies on this board (col/row within the lattice, layer within range). */
    public boolean inBounds(Post post) {
        return post.col() >= 0 && post.col() <= width
                && post.row() >= 0 && post.row() <= height
                && post.layer() >= 0 && post.layer() < layers;
    }

    /** Whether {@code placement} fits on the board and its edge isn't already occupied. */
    public boolean canPlace(SnapPlacement placement) {
        return inBounds(placement.postA())
                && inBounds(placement.postB())
                && !placements.containsKey(placement.edgeKey());
    }

    /**
     * Places {@code placement} if {@link #canPlace} allows it.
     *
     * @return {@code true} if placed, {@code false} if out of bounds or the edge is occupied.
     */
    public boolean place(SnapPlacement placement) {
        if (!canPlace(placement)) {
            return false;
        }
        placements.put(placement.edgeKey(), placement);
        return true;
    }

    /** Removes whatever part occupies the edge between {@code a} and {@code b}. */
    public SnapPlacement remove(Post a, Post b) {
        return placements.remove(SnapPlacement.EdgeKey.of(a, b));
    }

    /**
     * Grows the board to at least {@code newWidth × newHeight} slots. Extend-only: shrinking below the
     * current size (which could strand placed parts) is rejected.
     */
    public void resize(int newWidth, int newHeight) {
        if (newWidth < width || newHeight < height) {
            throw new IllegalArgumentException("board can only grow (extensible); got "
                    + newWidth + "x" + newHeight + " vs current " + width + "x" + height);
        }
        this.width = newWidth;
        this.height = newHeight;
    }

    /**
     * Rebuilds {@code world}'s electrical graph from the current board state. Clears any circuits the
     * world already holds, then derives fresh nodes/elements from every placement. Safe to call
     * repeatedly (idempotent for a given board state).
     */
    public void rebuild(ServerWorld world) {
        for (Circuit existing : new ArrayList<>(world.getCircuits())) {
            world.removeCircuit(existing);
        }
        PostGrid grid = new PostGrid(world);
        // Pass 1: connectors (wires) union their posts, so devices in pass 2 resolve to merged nodes.
        for (SnapPlacement placement : placements.values()) {
            if (placement.type().isConnector()) {
                placement.type().build(placement, world, grid);
            }
        }
        // Pass 2: devices attach their core element between the (now-merged) post nodes.
        for (SnapPlacement placement : placements.values()) {
            if (!placement.type().isConnector()) {
                placement.type().build(placement, world, grid);
            }
        }
    }

    /** Convenience for callers/tests: place a unit part in one call. */
    public boolean place(SnapPartType type, int col, int row, int layer, Facing facing) {
        return place(new SnapPlacement(type, col, row, layer, facing));
    }

    /** Convenience: place a unit part carrying an explicit electrical value (ohms / volts). */
    public boolean place(SnapPartType type, int col, int row, int layer, Facing facing, double value) {
        return place(new SnapPlacement(type, col, row, layer, facing, value));
    }

    /** Placements as a mutable snapshot list (for persistence/iteration without exposing the map). */
    public List<SnapPlacement> snapshot() {
        return new ArrayList<>(placements.values());
    }
}
