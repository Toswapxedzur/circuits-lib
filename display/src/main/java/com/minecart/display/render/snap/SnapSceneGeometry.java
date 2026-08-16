package com.minecart.display.render.snap;

import com.minecart.snap.Facing;
import com.minecart.snap.Post;
import com.minecart.snap.SnapBoard;
import com.minecart.snap.SnapPlacement;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure mapping from a {@link SnapBoard} to a list of {@link BoxSpec}s in 3D world units — no libGDX, so it
 * is unit-testable without a GL context. The renderer draws whatever this produces.
 *
 * <h2>Units (pixelated spec)</h2>
 * One slot spans {@link #CELL} world units on each axis (the "16×16 px" footprint), and a part stands
 * {@link #PART_HEIGHT} units tall (the "height = 4"), preserving the 16:4 proportion. Posts are the lattice
 * points {@code (col,row)}; world position of a post on layer {@code l} is
 * {@code (col*CELL, l*PART_HEIGHT, row*CELL)} with Y up. The baseboard top sits at {@code y = 0}; layer
 * {@code l} parts occupy {@code y ∈ [l*PART_HEIGHT, (l+1)*PART_HEIGHT]}.
 */
public final class SnapSceneGeometry {

    /** World units per slot (the 16×16 footprint). */
    public static final float CELL = 16f;
    /** Part height in world units (the "height = 4"). */
    public static final float PART_HEIGHT = 4f;
    /** Baseboard slab thickness (below y=0). */
    public static final float BASE_THICKNESS = 2f;
    /** Cross-section (width/depth) of a device part's bar. */
    public static final float PART_CROSS = 6f;
    /** Cube size of a rendered post marker. */
    public static final float POST_SIZE = 2f;
    /** Extra margin around the board slab beyond the outermost posts. */
    public static final float BASE_MARGIN = CELL;

    private SnapSceneGeometry() {}

    /** Builds the full scene (baseboard slab, post markers, and one bar per placed part). */
    public static List<BoxSpec> build(SnapBoard board) {
        List<BoxSpec> boxes = new ArrayList<>();
        addBase(board, boxes);
        addPosts(board, boxes);
        for (SnapPlacement placement : board.snapshot()) {
            boxes.add(partBox(placement));
        }
        return boxes;
    }

    private static void addBase(SnapBoard board, List<BoxSpec> out) {
        float spanX = board.width() * CELL;
        float spanZ = board.height() * CELL;
        out.add(new BoxSpec(
                spanX / 2f, -BASE_THICKNESS / 2f, spanZ / 2f,
                spanX + BASE_MARGIN, BASE_THICKNESS, spanZ + BASE_MARGIN,
                BoxSpec.Category.BASE));
    }

    private static void addPosts(SnapBoard board, List<BoxSpec> out) {
        for (int col = 0; col <= board.width(); col++) {
            for (int row = 0; row <= board.height(); row++) {
                out.add(new BoxSpec(
                        col * CELL, 0f, row * CELL,
                        POST_SIZE, POST_SIZE, POST_SIZE,
                        BoxSpec.Category.POST));
            }
        }
    }

    /** The bar for one device/connector part, spanning its two posts on its layer. */
    static BoxSpec partBox(SnapPlacement placement) {
        Post a = placement.postA();
        Post b = placement.postB();
        float ax = a.col() * CELL, az = a.row() * CELL;
        float bx = b.col() * CELL, bz = b.row() * CELL;
        float cx = (ax + bx) / 2f;
        float cz = (az + bz) / 2f;
        float cy = placement.layer() * PART_HEIGHT + PART_HEIGHT / 2f;

        // A unit part runs one cell along its facing axis; the other in-plane axis is the thin cross.
        boolean alongX = placement.facing() == Facing.EAST || placement.facing() == Facing.WEST;
        float sizeX = alongX ? CELL : PART_CROSS;
        float sizeZ = alongX ? PART_CROSS : CELL;

        return new BoxSpec(cx, cy, cz, sizeX, PART_HEIGHT, sizeZ, categoryOf(placement));
    }

    private static BoxSpec.Category categoryOf(SnapPlacement placement) {
        return switch (placement.type().id()) {
            case "snap_wire" -> BoxSpec.Category.WIRE;
            case "snap_resistor" -> BoxSpec.Category.RESISTOR;
            case "snap_battery" -> BoxSpec.Category.BATTERY;
            default -> BoxSpec.Category.UNKNOWN;
        };
    }
}
