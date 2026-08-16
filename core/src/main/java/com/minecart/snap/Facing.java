package com.minecart.snap;

/**
 * The four in-plane cardinal directions a snap part can point on the baseboard grid. A part's second
 * post sits one lattice step from its origin in this direction, so facing is what turns a single part
 * model into the four rotations the player can place it at. Layer/stacking is a separate axis (see
 * {@link SnapPlacement#layer()}).
 */
public enum Facing {
    NORTH(0, 1),
    EAST(1, 0),
    SOUTH(0, -1),
    WEST(-1, 0);

    private final int dCol;
    private final int dRow;

    Facing(int dCol, int dRow) {
        this.dCol = dCol;
        this.dRow = dRow;
    }

    public int dCol() { return dCol; }
    public int dRow() { return dRow; }

    /** The next facing clockwise (N→E→S→W→N); used when the player rotates a ghost before placing. */
    public Facing rotateClockwise() {
        return switch (this) {
            case NORTH -> EAST;
            case EAST -> SOUTH;
            case SOUTH -> WEST;
            case WEST -> NORTH;
        };
    }
}
