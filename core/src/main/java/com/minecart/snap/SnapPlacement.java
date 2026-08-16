package com.minecart.snap;

/**
 * One snap part placed on the board: a {@link SnapPartType}, its origin post {@code (col,row,layer)}, the
 * {@link Facing} it points, and an optional scalar {@code value} (e.g. a resistor's ohms or a battery's
 * volts — {@link Double#NaN} means "use the part type's default"). For the current unit-segment parts the
 * two electrical pins are the origin post and the post one lattice step along {@code facing}; multi-slot
 * footprints will generalize this later.
 */
public final class SnapPlacement {

    private final SnapPartType type;
    private final int col;
    private final int row;
    private final int layer;
    private final Facing facing;
    private final double value;

    public SnapPlacement(SnapPartType type, int col, int row, int layer, Facing facing) {
        this(type, col, row, layer, facing, Double.NaN);
    }

    public SnapPlacement(SnapPartType type, int col, int row, int layer, Facing facing, double value) {
        if (type == null) throw new IllegalArgumentException("type");
        if (facing == null) throw new IllegalArgumentException("facing");
        this.type = type;
        this.col = col;
        this.row = row;
        this.layer = layer;
        this.facing = facing;
        this.value = value;
    }

    public SnapPartType type() { return type; }
    public int col() { return col; }
    public int row() { return row; }
    public int layer() { return layer; }
    public Facing facing() { return facing; }

    /** Configured scalar, or {@code fallback} when this placement left it unset ({@link Double#NaN}). */
    public double valueOr(double fallback) {
        return Double.isNaN(value) ? fallback : value;
    }

    /** Raw configured scalar ({@link Double#NaN} when unset). */
    public double value() { return value; }

    /** The origin electrical pin. */
    public Post postA() {
        return new Post(col, row, layer);
    }

    /** The far electrical pin, one lattice step along {@link #facing()}. */
    public Post postB() {
        return new Post(col + facing.dCol(), row + facing.dRow(), layer);
    }

    /**
     * Order-independent key identifying the board edge this part occupies, so two placements on the same
     * two posts (regardless of which end is the origin) collide. Used by {@link SnapBoard} to reject
     * double-placement on one edge.
     */
    public EdgeKey edgeKey() {
        return EdgeKey.of(postA(), postB());
    }

    /** Canonical unordered pair of posts. */
    public record EdgeKey(Post lo, Post hi) {
        static EdgeKey of(Post a, Post b) {
            // Deterministic ordering so (a,b) and (b,a) produce the same key.
            if (compare(a, b) <= 0) {
                return new EdgeKey(a, b);
            }
            return new EdgeKey(b, a);
        }

        private static int compare(Post a, Post b) {
            if (a.layer() != b.layer()) return Integer.compare(a.layer(), b.layer());
            if (a.col() != b.col()) return Integer.compare(a.col(), b.col());
            return Integer.compare(a.row(), b.row());
        }
    }
}
