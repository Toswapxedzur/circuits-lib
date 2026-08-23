package com.minecart.display.render.snap;

import com.minecart.snap.BoxSpec;
import com.minecart.snap.SnapSceneGeometry;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.minecart.snap.SnapBoard;
import com.minecart.snap.SnapPlacement;

import java.util.ArrayList;
import java.util.List;

/**
 * A renderable snapshot of a {@link SnapBoard}: the full list of {@link BoxSpec}s to draw, plus a
 * ray-pickable {@link BoundingBox} for every placed part (the baseboard and posts aren't pickable). The
 * screen casts a ray from the cursor and tests it against each {@link Pickable#bounds()} to find the part
 * under the mouse.
 */
public final class SnapScene {

    /** A part and its world-space bounding box, for ray-picking. */
    public record Pickable(SnapPlacement placement, BoundingBox bounds, BoxSpec box) {}

    private final List<BoxSpec> boxes;
    private final List<Pickable> pickables;

    private SnapScene(List<BoxSpec> boxes, List<Pickable> pickables) {
        this.boxes = boxes;
        this.pickables = pickables;
    }

    public List<BoxSpec> boxes() { return boxes; }
    public List<Pickable> pickables() { return pickables; }

    /** Builds the drawable + pickable scene for {@code board}. */
    public static SnapScene of(SnapBoard board) {
        List<BoxSpec> boxes = SnapSceneGeometry.build(board);
        List<Pickable> pickables = new ArrayList<>();
        for (SnapPlacement placement : board.snapshot()) {
            BoxSpec box = SnapSceneGeometry.partBox(placement);
            pickables.add(new Pickable(placement, boundsOf(box), box));
        }
        return new SnapScene(boxes, pickables);
    }

    static BoundingBox boundsOf(BoxSpec box) {
        float hx = box.sizeX() / 2f, hy = box.sizeY() / 2f, hz = box.sizeZ() / 2f;
        return new BoundingBox(
                new Vector3(box.cx() - hx, box.cy() - hy, box.cz() - hz),
                new Vector3(box.cx() + hx, box.cy() + hy, box.cz() + hz));
    }
}
