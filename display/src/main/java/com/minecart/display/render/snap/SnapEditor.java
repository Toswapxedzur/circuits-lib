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
 * The client-side editing brain for the 3D snap board.
 *
 * <h2>Direction — internal heading, snapped externally</h2>
 * Scrolling nudges a continuous internal heading ({@link #dirAngle}) by a small step, and the actual
 * placement direction is the nearest <em>viable</em> discrete direction to it (preferring ones that keep
 * the part on the board). Directions come from the part's length via {@link SnapDirections} (not limited
 * to orthogonal). This makes the wheel feel smooth/slow and always land on a usable direction.
 *
 * <h2>Terminal targeting</h2>
 * When the crosshair is on a part, {@link #update} figures out which of that part's two terminal bumps the
 * ray is nearest and stacks the new part on that terminal, so you stack on the end you're actually aiming
 * at. {@code ←/→} flip which terminal is anchored (mirrors the part; battery polarity).
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
    private final Vector3 hoveredHit = new Vector3();

    private Tool tool = Tool.WIRE;
    private List<int[]> directions = SnapDirections.forLength(Tool.WIRE.type().length());
    private float dirAngle;
    private boolean flipped;

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

    /** The ghost's full geometry (body + terminal bumps), or empty when there's no valid anchor. */
    public List<BoxSpec> ghostBoxes() {
        return ghost == null ? List.of() : SnapSceneGeometry.partBoxes(ghost);
    }

    public void select(Tool tool) {
        this.tool = tool;
        this.directions = SnapDirections.forLength(tool.type().length());
    }

    /** Selects a hotbar slot by 0-based index; ignored if out of range. */
    public void selectIndex(int index) {
        Tool[] tools = Tool.values();
        if (index >= 0 && index < tools.length) {
            select(tools[index]);
        }
    }

    /** Flips the anchor port (mirrors the part to the other side of the crosshair; battery polarity). */
    public void flipPort() {
        flipped = !flipped;
    }

    /** Nudges the internal heading by {@code deltaDeg}; the placed direction snaps to the nearest viable. */
    public void nudgeDirection(float deltaDeg) {
        dirAngle = ((dirAngle + deltaDeg) % 360f + 360f) % 360f;
    }

    /** Recomputes the hovered part and placement ghost from the crosshair ray. */
    public void update(PerspectiveCamera camera, SnapScene scene) {
        hovered = pick(camera, scene);

        if (hovered != null) {
            // Stack on the terminal of the hovered part that the ray is nearest.
            SnapPlacement below = hovered.placement();
            Post target = nearerTerminal(below, hoveredHit);
            anchorCol = target.col();
            anchorRow = target.row();
            anchorLayer = below.layer() + 1;
        } else if (Intersector.intersectRayPlane(centerRay(camera), ground, groundHit)) {
            float spacing = SnapSceneGeometry.BUMP_SPACING;
            anchorCol = MathUtils.clamp(Math.round(groundHit.x / spacing), 0, board.width());
            anchorRow = MathUtils.clamp(Math.round(groundHit.z / spacing), 0, board.height());
            anchorLayer = 0;
        } else {
            ghost = null;
            ghostValid = false;
            return;
        }

        int[] d = chooseDirection(anchorCol, anchorRow, anchorLayer);
        int dc = flipped ? -d[0] : d[0];
        int dr = flipped ? -d[1] : d[1];
        ghost = new SnapPlacement(tool.type(), anchorCol, anchorRow, anchorLayer, dc, dr, flipped, Double.NaN);
        ghostValid = board.canPlace(ghost);
    }

    /** The discrete direction nearest the internal heading, preferring ones that stay on the board. */
    private int[] chooseDirection(int col, int row, int layer) {
        int[] best = directions.get(0);
        double bestScore = Double.MAX_VALUE;
        for (int[] d : directions) {
            int dc = flipped ? -d[0] : d[0];
            int dr = flipped ? -d[1] : d[1];
            double ang = Math.toDegrees(Math.atan2(dr, dc));
            double score = angularDistance(ang, dirAngle) + (board.inBounds(new Post(col + dc, row + dr, layer)) ? 0 : 1000);
            if (score < bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }

    private static double angularDistance(double a, double b) {
        double d = Math.abs(a - b) % 360.0;
        return d > 180.0 ? 360.0 - d : d;
    }

    /** Which of a part's two terminal bumps is nearer the ray-hit point (in the ground plane). */
    private static Post nearerTerminal(SnapPlacement p, Vector3 hit) {
        Post o = p.originPost();
        Post f = p.farPost();
        float s = SnapSceneGeometry.BUMP_SPACING;
        float dO = dist2(hit.x, hit.z, o.col() * s, o.row() * s);
        float dF = dist2(hit.x, hit.z, f.col() * s, f.row() * s);
        return dO <= dF ? o : f;
    }

    private static float dist2(float x, float z, float ox, float oz) {
        float dx = x - ox, dz = z - oz;
        return dx * dx + dz * dz;
    }

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
                    hoveredHit.set(boundsHit);
                }
            }
        }
        return best;
    }
}
