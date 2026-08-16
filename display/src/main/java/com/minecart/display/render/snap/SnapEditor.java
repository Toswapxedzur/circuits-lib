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

import java.util.ArrayList;
import java.util.List;

/**
 * The client-side editing brain for the 3D snap board.
 *
 * <h2>Direction — internal heading, external snapped, smoothly shown</h2>
 * Scrolling nudges a continuous internal heading ({@link #dirAngle}). The <em>placement</em> uses the
 * nearest viable discrete direction, but the <em>ghost</em> is drawn at a separately-eased
 * {@link #displayedAngle} that rotates toward the snapped direction — so it visibly sweeps and settles
 * rather than teleporting. Directions come from the part's length via {@link SnapDirections} (not limited
 * to orthogonal).
 *
 * <h2>Terminals</h2>
 * When the crosshair is on a part, the new part stacks on whichever of its two terminal bumps the ray is
 * nearest. Flipping (←/→) reverses which way the part extends from the crosshair bump — visibly swinging
 * it to the other side (and swapping battery polarity).
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
    private float dirAngle;       // internal continuous heading (deg)
    private float displayedAngle; // eased heading actually drawn (deg)

    private int anchorCol, anchorRow, anchorLayer;

    private SnapScene.Pickable hovered;
    private SnapPlacement ghost;
    private boolean ghostValid;

    public SnapEditor(SnapBoard board) {
        this.board = board;
    }

    public Tool tool() { return tool; }
    public SnapScene.Pickable hovered() { return hovered; }
    public SnapPlacement ghost() { return ghost; }
    public boolean ghostValid() { return ghost != null && ghostValid; }

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

    /** Flip terminal: reverse which way the part extends from the crosshair bump (180° — visible). */
    public void flipPort() {
        nudgeDirection(180f);
    }

    /** Nudges the internal heading; the placed direction snaps to the nearest viable, the ghost eases to it. */
    public void nudgeDirection(float deltaDeg) {
        dirAngle = ((dirAngle + deltaDeg) % 360f + 360f) % 360f;
    }

    /** Recomputes the hovered part and placement ghost from the crosshair ray. {@code dt} eases the ghost. */
    public void update(PerspectiveCamera camera, SnapScene scene, float dt) {
        hovered = pick(camera, scene);

        if (hovered != null) {
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
        ghost = new SnapPlacement(tool.type(), anchorCol, anchorRow, anchorLayer, d[0], d[1], false, Double.NaN);
        ghostValid = board.canPlace(ghost);

        // Ease the drawn angle toward the snapped direction's heading (shortest path).
        float target = (float) Math.toDegrees(Math.atan2(d[1], d[0]));
        float diff = ((target - displayedAngle + 540f) % 360f) - 180f;
        displayedAngle = (displayedAngle + diff * Math.min(1f, dt * 12f) + 360f) % 360f;
    }

    /** The ghost geometry to draw: a body bar at the eased angle plus its two terminal bumps. */
    public List<OrientedBox> ghostRender() {
        List<OrientedBox> out = new ArrayList<>(3);
        if (ghost == null) {
            return out;
        }
        float s = SnapSceneGeometry.BUMP_SPACING;
        float lenWorld = tool.type().length() * s;
        float ax = anchorCol * s, az = anchorRow * s;
        float rad = displayedAngle * MathUtils.degreesToRadians;
        float fx = ax + lenWorld * MathUtils.cos(rad);
        float fz = az + lenWorld * MathUtils.sin(rad);

        BoxSpec.Category cat = SnapSceneGeometry.partBox(ghost).category();
        float bodyY = SnapSceneGeometry.bodyCenterY(anchorLayer);
        float bodyLen = lenWorld + SnapSceneGeometry.COMPONENT_FOOTPRINT;
        out.add(new OrientedBox((ax + fx) / 2f, bodyY, (az + fz) / 2f,
                bodyLen, SnapSceneGeometry.COMPONENT_HEIGHT, SnapSceneGeometry.COMPONENT_FOOTPRINT,
                displayedAngle, cat));

        float bumpY = SnapSceneGeometry.bumpBottomY(anchorLayer + 1) + SnapSceneGeometry.BUMP_HEIGHT / 2f;
        float w = SnapSceneGeometry.BUMP_WIDTH, h = SnapSceneGeometry.BUMP_HEIGHT;
        out.add(new OrientedBox(ax, bumpY, az, w, h, w, 0f, BoxSpec.Category.BUMP));
        out.add(new OrientedBox(fx, bumpY, fz, w, h, w, 0f, BoxSpec.Category.BUMP));
        return out;
    }

    /** The discrete direction nearest the internal heading, preferring ones that stay on the board. */
    private int[] chooseDirection(int col, int row, int layer) {
        int[] best = directions.get(0);
        double bestScore = Double.MAX_VALUE;
        for (int[] d : directions) {
            double ang = Math.toDegrees(Math.atan2(d[1], d[0]));
            double score = angularDistance(ang, dirAngle)
                    + (board.inBounds(new Post(col + d[0], row + d[1], layer)) ? 0 : 1000);
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

    private static Post nearerTerminal(SnapPlacement p, Vector3 hit) {
        Post o = p.originPost();
        Post f = p.farPost();
        float s = SnapSceneGeometry.BUMP_SPACING;
        return dist2(hit.x, hit.z, o.col() * s, o.row() * s) <= dist2(hit.x, hit.z, f.col() * s, f.row() * s)
                ? o : f;
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
