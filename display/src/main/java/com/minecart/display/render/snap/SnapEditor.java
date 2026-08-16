package com.minecart.display.render.snap;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Plane;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.minecart.snap.AllSnapParts;
import com.minecart.snap.Post;
import com.minecart.snap.SnapBoard;
import com.minecart.snap.SnapDirections;
import com.minecart.snap.SnapPartType;
import com.minecart.snap.SnapPlacement;

import java.util.List;

/**
 * The client-side editing brain for the 3D snap board: which item is selected, the direction and
 * anchor-port the next part will use, and — recomputed every frame from the crosshair ray — the part
 * under the crosshair (for editing/removal / stacking) and the candidate placement (the "ghost") with its
 * validity.
 *
 * <p>The scroll wheel {@link #cycleDirection cycles the direction}, snapping to directions that keep the
 * part on the board; the direction set comes from the part's length via {@link SnapDirections} (so it is
 * not limited to orthogonal — a longer part gets diagonal directions too). {@code F} {@link #flipPort()
 * flips the anchor port} (which terminal sits on the crosshair bump — a battery's polarity). It never
 * mutates the board; {@link SnapScreen} reads {@link #ghost()} / {@link #hovered()} and submits edits.
 */
public final class SnapEditor {

    /** Hotbar entries: the placeable parts. (Removal/edit is right-click, Minecraft-style — not a tool.) */
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
    private List<int[]> directions = SnapDirections.forLength(Tool.WIRE.type().length());
    private int dirIndex;
    private boolean flipped;

    // Last anchor bump computed in update(), so cycleDirection() can snap to in-bounds directions.
    private int anchorCol, anchorRow, anchorLayer;

    private SnapScene.Pickable hovered;
    private SnapPlacement ghost;
    private boolean ghostValid;

    public SnapEditor(SnapBoard board) {
        this.board = board;
    }

    public Tool tool() { return tool; }
    public boolean flipped() { return flipped; }
    public SnapScene.Pickable hovered() { return hovered; }
    public SnapPlacement ghost() { return ghost; }
    public boolean ghostValid() { return ghost != null && ghostValid; }

    /** The ghost as a render box, or {@code null} when there's no valid anchor. */
    public BoxSpec ghostBox() {
        return ghost == null ? null : SnapSceneGeometry.partBox(ghost);
    }

    public void select(Tool tool) {
        this.tool = tool;
        this.directions = SnapDirections.forLength(tool.type().length());
        this.dirIndex = Math.min(dirIndex, directions.size() - 1);
    }

    /** Selects a hotbar slot by 0-based index; ignored if out of range. */
    public void selectIndex(int index) {
        Tool[] tools = Tool.values();
        if (index >= 0 && index < tools.length) {
            select(tools[index]);
        }
    }

    /** Cycles the hotbar item by {@code dir} (+1/-1), wrapping. */
    public void cycleTool(int dir) {
        Tool[] tools = Tool.values();
        selectIndex(((tool.ordinal() + dir) % tools.length + tools.length) % tools.length);
    }

    /** Flips the anchor port (swaps which terminal sits on the crosshair bump; battery polarity). */
    public void flipPort() {
        flipped = !flipped;
    }

    /**
     * Cycles the placement direction by {@code step}, snapping to the next direction whose far bump stays
     * on the board (so scroll lands on usable directions rather than off-board ones).
     */
    public void cycleDirection(int step) {
        if (directions.isEmpty()) {
            return;
        }
        int n = directions.size();
        for (int k = 1; k <= n; k++) {
            int idx = ((dirIndex + step * k) % n + n) % n;
            int[] d = directions.get(idx);
            if (board.inBounds(new Post(anchorCol + d[0], anchorRow + d[1], anchorLayer))) {
                dirIndex = idx;
                return;
            }
        }
        dirIndex = ((dirIndex + step) % n + n) % n; // nothing in bounds: still advance one
    }

    /** Recomputes the hovered part and placement ghost from the crosshair ray. */
    public void update(PerspectiveCamera camera, SnapScene scene) {
        hovered = pick(camera, scene);

        if (hovered != null) {
            // Stack the new part one level above the part under the crosshair, on its origin bump.
            SnapPlacement below = hovered.placement();
            anchorCol = below.col();
            anchorRow = below.row();
            anchorLayer = below.layer() + 1;
        } else if (Intersector.intersectRayPlane(centerRay(camera), ground, groundHit)) {
            // Snap to the nearest bump where the crosshair ray meets the ground plane (y = 0).
            float spacing = SnapSceneGeometry.BUMP_SPACING;
            anchorCol = MathUtils.clamp(Math.round(groundHit.x / spacing), 0, board.width());
            anchorRow = MathUtils.clamp(Math.round(groundHit.z / spacing), 0, board.height());
            anchorLayer = 0;
        } else {
            ghost = null;
            ghostValid = false;
            return;
        }

        int[] d = directions.get(Math.min(dirIndex, directions.size() - 1));
        ghost = new SnapPlacement(tool.type(), anchorCol, anchorRow, anchorLayer, d[0], d[1], flipped, Double.NaN);
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
