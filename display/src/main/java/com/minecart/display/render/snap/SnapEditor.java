package com.minecart.display.render.snap;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Plane;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.minecart.snap.AllSnapParts;
import com.minecart.snap.Facing;
import com.minecart.snap.SnapBoard;
import com.minecart.snap.SnapPartType;
import com.minecart.snap.SnapPlacement;

/**
 * The client-side editing brain for the 3D snap board: which item/tool is selected, the rotation the next
 * part will be placed at, and — recomputed every frame from the camera ray — the part currently under the
 * cursor (for deletion / stacking) and the candidate placement (the "ghost") with its validity.
 *
 * <p>It never mutates the board itself. {@link SnapScreen} reads {@link #ghost()} / {@link #hovered()} to
 * render the shadow + highlight, and on a click submits the resulting {@link #ghost()} or deletion through
 * the server's action queue so edits stay authoritative.
 */
public final class SnapEditor {

    /** Hotbar entries: the placeable parts. (Removal is right-click, Minecraft-style — not a tool.) */
    public enum Tool {
        WIRE("Wire", AllSnapParts.SNAP_WIRE),
        RESISTOR("Resistor", AllSnapParts.SNAP_RESISTOR),
        BATTERY("Battery", AllSnapParts.SNAP_BATTERY);

        private final String label;
        private final SnapPartType type;

        Tool(String label, SnapPartType type) {
            this.label = label;
            this.type = type;
        }

        public String label() { return label; }
        public SnapPartType type() { return type; }
    }

    private final SnapBoard board;
    private final Plane ground = new Plane(new Vector3(0f, 1f, 0f), 0f);
    private final Vector3 groundHit = new Vector3();
    private final Vector3 boundsHit = new Vector3();

    private Tool tool = Tool.WIRE;
    private Facing facing = Facing.EAST;

    private SnapScene.Pickable hovered;
    private SnapPlacement ghost;
    private boolean ghostValid;

    public SnapEditor(SnapBoard board) {
        this.board = board;
    }

    public Tool tool() { return tool; }
    public Facing facing() { return facing; }
    public SnapScene.Pickable hovered() { return hovered; }
    public SnapPlacement ghost() { return ghost; }
    public boolean ghostValid() { return ghost != null && ghostValid; }

    /** The ghost as a render box, or {@code null} when there's nothing to preview (e.g. the delete tool). */
    public BoxSpec ghostBox() {
        return ghost == null ? null : SnapSceneGeometry.partBox(ghost);
    }

    public void select(Tool tool) {
        this.tool = tool;
    }

    /** Selects a hotbar slot by 0-based index; ignored if out of range. */
    public void selectIndex(int index) {
        Tool[] tools = Tool.values();
        if (index >= 0 && index < tools.length) {
            this.tool = tools[index];
        }
    }

    /** Cycles the hotbar selection by {@code dir} (+1 / -1), wrapping — driven by the scroll wheel. */
    public void cycle(int dir) {
        Tool[] tools = Tool.values();
        int i = ((tool.ordinal() + dir) % tools.length + tools.length) % tools.length;
        this.tool = tools[i];
    }

    /** Rotates the placement facing clockwise (N→E→S→W). */
    public void rotate() {
        facing = facing.rotateClockwise();
    }

    /** Recomputes the hovered part and placement ghost from the crosshair ray. */
    public void update(PerspectiveCamera camera, SnapScene scene) {
        hovered = pick(camera, scene);

        int col, row, layer;
        if (hovered != null) {
            // Stack the new part one level above the part under the crosshair, on its origin bump.
            SnapPlacement below = hovered.placement();
            col = below.col();
            row = below.row();
            layer = below.layer() + 1;
        } else {
            // Snap to the nearest bump where the crosshair ray meets the ground plane (y = 0).
            if (!Intersector.intersectRayPlane(centerRay(camera), ground, groundHit)) {
                ghost = null;
                ghostValid = false;
                return;
            }
            float spacing = SnapSceneGeometry.BUMP_SPACING;
            col = MathUtils.clamp(Math.round(groundHit.x / spacing), 0, board.width());
            row = MathUtils.clamp(Math.round(groundHit.z / spacing), 0, board.height());
            layer = 0;
        }

        ghost = new SnapPlacement(tool.type(), col, row, layer, facing);
        ghostValid = board.canPlace(ghost);
    }

    /** The ray through the screen centre — the crosshair — since the cursor is locked in look mode. */
    private static Ray centerRay(PerspectiveCamera camera) {
        return camera.getPickRay(Gdx.graphics.getWidth() / 2f, Gdx.graphics.getHeight() / 2f);
    }

    private SnapScene.Pickable pick(PerspectiveCamera camera, SnapScene scene) {
        Ray ray = centerRay(camera);
        SnapScene.Pickable best = null;
        float bestDist2 = Float.MAX_VALUE;
        for (SnapScene.Pickable pk : scene.pickables()) {
            if (Intersector.intersectRayBounds(ray, pk.bounds(), boundsHit)) {
                float d2 = boundsHit.dst2(camera.position);
                if (d2 < bestDist2) {
                    bestDist2 = d2;
                    best = pk;
                }
            }
        }
        return best;
    }
}
