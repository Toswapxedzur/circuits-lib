package com.minecart.snap;

import com.minecart.foundation.Circuit;
import com.minecart.logic.ServerWorld;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.ListTag;
import com.minecart.serialization.tag.Tag;

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

    // The bump-slots (col,row,level) currently claimed by a component terminal. Real Snap-Circuit rule:
    // at most one component snaps onto a given bump at a given level ("two components don't share a bump");
    // to connect, you stack — a component provides top bumps at level+1, and a part at level L needs each
    // terminal supported by a component below it (a claimed slot at level-1), or level 0 (the base).
    private final java.util.Set<Post> bumpSlots = new java.util.HashSet<>();

    // Bumped on every structural change. In snap mode the render thread reads the board (via snapshot())
    // to build the scene while the tick thread mutates it (edits are submitted to the server's action
    // queue), so all placement access is synchronized on {@code this} and the render thread rebuilds its
    // scene whenever this revision advances.
    private int revision;

    /** Default starting board dimensions for a freshly created snap world (extensible thereafter). */
    public static final int DEFAULT_WIDTH = 8;
    public static final int DEFAULT_HEIGHT = 8;
    public static final int DEFAULT_LAYERS = 3;

    public SnapBoard(int width, int height, int layers) {
        if (width < 1 || height < 1 || layers < 1) {
            throw new IllegalArgumentException("board dimensions must be >= 1");
        }
        this.width = width;
        this.height = height;
        this.layers = layers;
    }

    /** A fresh empty board at the default starting size. */
    public static SnapBoard createDefault() {
        return new SnapBoard(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_LAYERS);
    }

    /**
     * A default-sized board pre-populated with a small demo scene: one battery/resistor loop on layer 0
     * plus a couple of parts on layer 1 (varied orientations and heights). Temporary — new snap worlds
     * seed this so the 3D view has something to render and pick until the placement UI lands; it will
     * revert to {@link #createDefault()} (empty) then.
     */
    public static SnapBoard createDemo() {
        AllSnapParts.init();
        SnapBoard board = createDefault();
        // A battery on the base with a resistor STACKED directly on top: the two terminals share the same
        // bump columns via stacking, forming a V/R loop — the real way parts connect in Snap Circuits.
        board.place(AllSnapParts.SNAP_BATTERY, 2, 2, 0, Facing.EAST, 5.0);    // (2,2)->(3,2) level 0
        board.place(AllSnapParts.SNAP_RESISTOR, 2, 2, 1, Facing.EAST, 10.0);  // stacked on top -> loop
        // A second stack elsewhere for visual variety (resistor on the base, battery stacked on it -> loop).
        board.place(AllSnapParts.SNAP_RESISTOR, 5, 5, 0, Facing.NORTH, 22.0); // (5,5)->(5,6) level 0
        board.place(AllSnapParts.SNAP_BATTERY, 5, 5, 1, Facing.NORTH, 3.0);   // stacked -> loop
        return board;
    }

    public int width() { return width; }
    public int height() { return height; }
    public int layers() { return layers; }

    /** All placed parts, in insertion order. Prefer {@link #snapshot()} for cross-thread reads. */
    public synchronized Collection<SnapPlacement> placements() {
        return new ArrayList<>(placements.values());
    }

    /** A monotonically increasing counter bumped on every structural change (place/remove/resize). */
    public synchronized int revision() {
        return revision;
    }

    /** Whether {@code post} lies on this board (col/row within the lattice, layer within range). */
    public boolean inBounds(Post post) {
        return post.col() >= 0 && post.col() <= width
                && post.row() >= 0 && post.row() <= height
                && post.layer() >= 0 && post.layer() < layers;
    }

    /**
     * Whether {@code placement} can go here: both terminal bumps on the board, no existing part on the
     * same edge, neither terminal bump already claimed at this level ("no shared bump"), and both terminals
     * supported (level 0, or a component directly below providing a top bump).
     */
    public synchronized boolean canPlace(SnapPlacement placement) {
        Post a = placement.postA();
        Post b = placement.postB();
        return inBounds(a) && inBounds(b)
                && !placements.containsKey(placement.edgeKey())
                && !bumpSlots.contains(a) && !bumpSlots.contains(b)
                && supported(a) && supported(b);
    }

    /** A terminal is supported at level 0 (the base) or if a component below claims the same bump one level down. */
    private boolean supported(Post terminal) {
        return terminal.layer() == 0
                || bumpSlots.contains(new Post(terminal.col(), terminal.row(), terminal.layer() - 1));
    }

    /**
     * Places {@code placement} if {@link #canPlace} allows it, claiming its two terminal bump-slots.
     *
     * @return {@code true} if placed, {@code false} if invalid.
     */
    public synchronized boolean place(SnapPlacement placement) {
        if (!canPlace(placement)) {
            return false;
        }
        placeUnchecked(placement);
        return true;
    }

    /** Adds a placement and claims its bump-slots without validation (used when loading a trusted save). */
    public synchronized void placeUnchecked(SnapPlacement placement) {
        placements.put(placement.edgeKey(), placement);
        bumpSlots.add(placement.postA());
        bumpSlots.add(placement.postB());
        revision++;
    }

    /** Removes whatever part occupies the edge between {@code a} and {@code b}, freeing its bump-slots. */
    public synchronized SnapPlacement remove(Post a, Post b) {
        SnapPlacement removed = placements.remove(SnapPlacement.EdgeKey.of(a, b));
        if (removed != null) {
            bumpSlots.remove(removed.postA());
            bumpSlots.remove(removed.postB());
            revision++;
        }
        return removed;
    }

    /**
     * Grows the board to at least {@code newWidth × newHeight} slots. Extend-only: shrinking below the
     * current size (which could strand placed parts) is rejected.
     */
    public synchronized void resize(int newWidth, int newHeight) {
        if (newWidth < width || newHeight < height) {
            throw new IllegalArgumentException("board can only grow (extensible); got "
                    + newWidth + "x" + newHeight + " vs current " + width + "x" + height);
        }
        this.width = newWidth;
        this.height = newHeight;
        revision++;
    }

    /**
     * Rebuilds {@code world}'s electrical graph from the current board state. Clears any circuits the
     * world already holds, then derives fresh nodes/elements from every placement. Safe to call
     * repeatedly (idempotent for a given board state).
     */
    public synchronized void rebuild(ServerWorld world) {
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
    public synchronized List<SnapPlacement> snapshot() {
        return new ArrayList<>(placements.values());
    }

    // --- Serialization ---------------------------------------------------------------------------
    // The board (dimensions + placements) is the authoritative persisted state for a snap world; the
    // electrical circuit is derived from it via rebuild() and is NOT saved. On load the board is restored
    // and rebuilt, so a reloaded snap world ticks identically to before it was saved.

    private static final String TAG_WIDTH = "width";
    private static final String TAG_HEIGHT = "height";
    private static final String TAG_LAYERS = "layers";
    private static final String TAG_PARTS = "parts";
    private static final String TAG_PART_ID = "part";
    private static final String TAG_COL = "col";
    private static final String TAG_ROW = "row";
    private static final String TAG_LAYER = "layer";
    private static final String TAG_FACING = "facing";
    private static final String TAG_VALUE = "value";

    /** Writes dimensions and every placement into {@code tag}. */
    public synchronized void save(CompoundTag tag) {
        tag.putInt(TAG_WIDTH, width);
        tag.putInt(TAG_HEIGHT, height);
        tag.putInt(TAG_LAYERS, layers);
        ListTag list = new ListTag();
        for (SnapPlacement p : placements.values()) {
            CompoundTag pt = new CompoundTag();
            pt.putString(TAG_PART_ID, p.type().id());
            pt.putInt(TAG_COL, p.col());
            pt.putInt(TAG_ROW, p.row());
            pt.putInt(TAG_LAYER, p.layer());
            pt.putString(TAG_FACING, p.facing().name());
            if (!Double.isNaN(p.value())) {
                pt.putDouble(TAG_VALUE, p.value());
            }
            list.add(pt);
        }
        tag.put(TAG_PARTS, list);
    }

    /**
     * Reconstructs a board from a {@link #save(CompoundTag)} tag. Placements referencing an unregistered
     * part id, or that no longer fit the (possibly changed) board, are skipped rather than failing the
     * whole load.
     */
    public static SnapBoard load(CompoundTag tag) {
        int w = Math.max(1, tag.getInt(TAG_WIDTH));
        int h = Math.max(1, tag.getInt(TAG_HEIGHT));
        int l = Math.max(1, tag.getInt(TAG_LAYERS));
        SnapBoard board = new SnapBoard(w, h, l);
        Tag partsTag = tag.get(TAG_PARTS);
        if (partsTag instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                if (!(list.get(i) instanceof CompoundTag pt)) {
                    continue;
                }
                SnapPartType type = SnapPartRegistry.getType(pt.getString(TAG_PART_ID));
                if (type == null) {
                    continue;
                }
                Facing facing = parseFacing(pt.getString(TAG_FACING));
                double value = pt.get(TAG_VALUE) != null ? pt.getDouble(TAG_VALUE) : Double.NaN;
                board.placeUnchecked(new SnapPlacement(type, pt.getInt(TAG_COL), pt.getInt(TAG_ROW),
                        pt.getInt(TAG_LAYER), facing, value));
            }
        }
        return board;
    }

    private static Facing parseFacing(String name) {
        if (name != null) {
            for (Facing f : Facing.values()) {
                if (f.name().equals(name)) {
                    return f;
                }
            }
        }
        return Facing.NORTH;
    }
}
