package com.minecart.display.render.snap;

import com.minecart.snap.Facing;
import com.minecart.snap.Post;
import com.minecart.snap.SnapBoard;
import com.minecart.snap.SnapPlacement;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure mapping from a {@link SnapBoard} to a list of {@link BoxSpec}s in 3D world units — no libGDX, so it
 * is unit-testable without a GL context.
 *
 * <h2>Units — real Snap-Circuit dimensions (all in "pixels")</h2>
 * The base is a grid of <b>bumps</b> (posts) spaced {@link #BUMP_SPACING}=16 apart, each a
 * {@link #BUMP_WIDTH}=3 × {@link #BUMP_HEIGHT}=1 nub. A component snaps onto bumps with a
 * {@link #COMPONENT_FOOTPRINT}=9 wide, {@link #COMPONENT_HEIGHT}=4 tall body (longer when it spans several
 * bumps) and <b>generates its own bumps on top</b> so the next component stacks on it. One stack level is
 * {@link #LEVEL_HEIGHT} = component + bump tall. A bump at level {@code L} occupies {@code y ∈ [L·LEVEL,
 * L·LEVEL+1]}; a component at level {@code L} sits on those bumps ({@code y ∈ [L·LEVEL+1, L·LEVEL+5]}) and
 * its top bumps are the level {@code L+1} bumps.
 */
public final class SnapSceneGeometry {

    /** Grid spacing between bumps. */
    public static final float BUMP_SPACING = 16f;
    /** Bump nub width/depth. */
    public static final float BUMP_WIDTH = 3f;
    /** Bump nub height. */
    public static final float BUMP_HEIGHT = 1f;
    /** Component body footprint (width/depth over a single bump). */
    public static final float COMPONENT_FOOTPRINT = 9f;
    /** Component body height. */
    public static final float COMPONENT_HEIGHT = 4f;
    /** One stack level: a component plus the bump on top of it. */
    public static final float LEVEL_HEIGHT = COMPONENT_HEIGHT + BUMP_HEIGHT;
    /** Baseboard slab thickness (below y=0). */
    public static final float BASE_THICKNESS = 2f;
    /** Extra margin around the base slab beyond the outermost bumps. */
    public static final float BASE_MARGIN = BUMP_SPACING;

    private SnapSceneGeometry() {}

    /** World X of a bump column. */
    public static float worldX(int col) { return col * BUMP_SPACING; }
    /** World Z of a bump row. */
    public static float worldZ(int row) { return row * BUMP_SPACING; }
    /** Y of the bottom of a bump at stack level {@code level}. */
    public static float bumpBottomY(int level) { return level * LEVEL_HEIGHT; }
    /** Y centre of a component body at stack level {@code level}. */
    public static float bodyCenterY(int level) { return level * LEVEL_HEIGHT + BUMP_HEIGHT + COMPONENT_HEIGHT / 2f; }

    /** Builds the full scene: base slab, every base bump, and each component (body + its two top bumps). */
    public static List<BoxSpec> build(SnapBoard board) {
        List<BoxSpec> boxes = new ArrayList<>();
        addBase(board, boxes);
        addBaseBumps(board, boxes);
        for (SnapPlacement placement : board.snapshot()) {
            boxes.add(partBox(placement));
            boxes.add(topBump(placement.originPost(), placement.layer()));
            boxes.add(topBump(placement.farPost(), placement.layer()));
        }
        return boxes;
    }

    private static void addBase(SnapBoard board, List<BoxSpec> out) {
        float spanX = board.width() * BUMP_SPACING;
        float spanZ = board.height() * BUMP_SPACING;
        out.add(new BoxSpec(
                spanX / 2f, -BASE_THICKNESS / 2f, spanZ / 2f,
                spanX + BASE_MARGIN, BASE_THICKNESS, spanZ + BASE_MARGIN,
                BoxSpec.Category.BASE));
    }

    private static void addBaseBumps(SnapBoard board, List<BoxSpec> out) {
        for (int col = 0; col <= board.width(); col++) {
            for (int row = 0; row <= board.height(); row++) {
                out.add(bump(col, row, 0));
            }
        }
    }

    /** A bump nub at the given column/row and stack level. */
    static BoxSpec bump(int col, int row, int level) {
        return new BoxSpec(
                worldX(col), bumpBottomY(level) + BUMP_HEIGHT / 2f, worldZ(row),
                BUMP_WIDTH, BUMP_HEIGHT, BUMP_WIDTH, BoxSpec.Category.BUMP);
    }

    /** The top bump a component provides above one of its terminals (the level {@code level+1} bump). */
    static BoxSpec topBump(Post terminal, int level) {
        return bump(terminal.col(), terminal.row(), level + 1);
    }

    /**
     * The component body bar spanning its two bumps at its stack level. (Axis-aligned; a diagonal
     * (non-orthogonal) part would need a rotated bar — added when the first such part exists.)
     */
    public static BoxSpec partBox(SnapPlacement placement) {
        Post a = placement.originPost();
        Post b = placement.farPost();
        float ax = worldX(a.col()), az = worldZ(a.row());
        float bx = worldX(b.col()), bz = worldZ(b.row());
        float cx = (ax + bx) / 2f;
        float cz = (az + bz) / 2f;
        float cy = bodyCenterY(placement.layer());

        boolean alongX = a.row() == b.row();
        float span = alongX ? Math.abs(bx - ax) : Math.abs(bz - az);
        float lengthAlong = span + COMPONENT_FOOTPRINT;
        float sizeX = alongX ? lengthAlong : COMPONENT_FOOTPRINT;
        float sizeZ = alongX ? COMPONENT_FOOTPRINT : lengthAlong;

        return new BoxSpec(cx, cy, cz, sizeX, COMPONENT_HEIGHT, sizeZ, categoryOf(placement));
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
