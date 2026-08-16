package com.minecart.snap;

/**
 * One snap part placed on the board: a {@link SnapPartType}, its <b>origin</b> bump {@code (col,row,layer)},
 * a direction <b>offset</b> {@code (dCol,dRow)} to the far bump, an anchor-port {@code flipped} flag, and an
 * optional scalar {@code value} ({@link Double#NaN} → the type's default).
 *
 * <h2>Direction &amp; ports</h2>
 * The two physical bumps a part occupies are {@link #originPost()} and {@link #farPost()} = origin +
 * offset. The direction offset is a general integer vector (not limited to orthogonal): a length-{@code L}
 * part accepts any {@code (dCol,dRow)} with {@code dCol²+dRow² = L²} — e.g. a length-5 part accepts the
 * 3-4-5 set (see {@link SnapDirections}). The <b>anchor port</b> is which electrical terminal sits on the
 * origin bump: {@link #flipped()} swaps terminals A/B (e.g. a battery's polarity) without changing the two
 * occupied bumps. Electrical terminals are {@link #postA()} (−) and {@link #postB()} (+).
 */
public final class SnapPlacement {

    private final SnapPartType type;
    private final int col;
    private final int row;
    private final int layer;
    private final int dCol;
    private final int dRow;
    private final boolean flipped;
    private final double value;

    public SnapPlacement(SnapPartType type, int col, int row, int layer, int dCol, int dRow,
                         boolean flipped, double value) {
        if (type == null) throw new IllegalArgumentException("type");
        if (dCol == 0 && dRow == 0) throw new IllegalArgumentException("zero direction");
        this.type = type;
        this.col = col;
        this.row = row;
        this.layer = layer;
        this.dCol = dCol;
        this.dRow = dRow;
        this.flipped = flipped;
        this.value = value;
    }

    /** Convenience: a unit part in a cardinal {@link Facing}, unflipped, default value. */
    public SnapPlacement(SnapPartType type, int col, int row, int layer, Facing facing) {
        this(type, col, row, layer, facing.dCol(), facing.dRow(), false, Double.NaN);
    }

    /** Convenience: a unit part in a cardinal {@link Facing} with an explicit value. */
    public SnapPlacement(SnapPartType type, int col, int row, int layer, Facing facing, double value) {
        this(type, col, row, layer, facing.dCol(), facing.dRow(), false, value);
    }

    public SnapPartType type() { return type; }
    public int col() { return col; }
    public int row() { return row; }
    public int layer() { return layer; }
    public int dCol() { return dCol; }
    public int dRow() { return dRow; }
    public boolean flipped() { return flipped; }
    public double value() { return value; }

    /** Configured scalar, or {@code fallback} when unset ({@link Double#NaN}). */
    public double valueOr(double fallback) {
        return Double.isNaN(value) ? fallback : value;
    }

    /** The anchor bump (under the crosshair). */
    public Post originPost() {
        return new Post(col, row, layer);
    }

    /** The far bump, origin + direction offset. */
    public Post farPost() {
        return new Post(col + dCol, row + dRow, layer);
    }

    /** Electrical terminal A (−): the origin unless {@link #flipped()}. */
    public Post postA() {
        return flipped ? farPost() : originPost();
    }

    /** Electrical terminal B (+): the far bump unless {@link #flipped()}. */
    public Post postB() {
        return flipped ? originPost() : farPost();
    }

    /** Returns a copy with the anchor port flipped (terminals A/B swapped). */
    public SnapPlacement withFlipped(boolean flip) {
        return new SnapPlacement(type, col, row, layer, dCol, dRow, flip, value);
    }

    /** Returns a copy pointing in a new direction offset. */
    public SnapPlacement withDirection(int newDCol, int newDRow) {
        return new SnapPlacement(type, col, row, layer, newDCol, newDRow, flipped, value);
    }

    /**
     * Order-independent key identifying the two bumps this part occupies, so two parts on the same pair of
     * bumps (regardless of origin end) collide.
     */
    public EdgeKey edgeKey() {
        return EdgeKey.of(originPost(), farPost());
    }

    /** Canonical unordered pair of posts. */
    public record EdgeKey(Post lo, Post hi) {
        static EdgeKey of(Post a, Post b) {
            return compare(a, b) <= 0 ? new EdgeKey(a, b) : new EdgeKey(b, a);
        }

        private static int compare(Post a, Post b) {
            if (a.layer() != b.layer()) return Integer.compare(a.layer(), b.layer());
            if (a.col() != b.col()) return Integer.compare(a.col(), b.col());
            return Integer.compare(a.row(), b.row());
        }
    }
}
