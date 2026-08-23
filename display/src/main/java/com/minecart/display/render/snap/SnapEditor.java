package com.minecart.display.render.snap;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Plane;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.minecart.snap.AllSnapParts;
import com.minecart.snap.BoxSpec;
import com.minecart.snap.SnapSceneGeometry;
import com.minecart.snap.Post;
import com.minecart.snap.SnapBoard;
import com.minecart.snap.SnapDirections;
import com.minecart.snap.SnapPartType;
import com.minecart.snap.SnapPlacement;

import java.util.ArrayList;
import java.util.List;

/**
 * The client-side editing brain for the 3D snap board. Placement is discrete, but the ghost is drawn with
 * eased animations so it feels smooth, all keyed off the <b>crosshair bump as the pivot</b>:
 * <ul>
 *   <li><b>scroll</b> changes direction — placement snaps to the nearest viable discrete direction, and the
 *       ghost's drawn heading rotates to it <em>around the anchored terminal</em> (the pivot stays put);</li>
 *   <li><b>←/→</b> change which terminal sits on the crosshair — the part keeps its direction and slides in
 *       the XZ plane (the anchored local offset eases) so the chosen terminal moves onto the pivot.</li>
 * </ul>
 * The geometry is expressed via a part's list of local terminal offsets, so it already generalizes to
 * components with more than two terminals (only the local-offset table and the placement model need to
 * grow for those).
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

    private static final float EASE_RATE = 12f;

    private final SnapBoard board;
    private final Plane ground = new Plane(new Vector3(0f, 1f, 0f), 0f);
    private final Vector3 groundHit = new Vector3();
    private final Vector3 boundsHit = new Vector3();
    private final Vector3 hoveredHit = new Vector3();

    private Tool tool = Tool.WIRE;
    private List<int[]> directions = SnapDirections.forLength(Tool.WIRE.type().length());
    private float dirAngle;        // internal continuous heading (deg)
    private float displayedAngle;  // eased heading actually drawn (deg)
    // Eased local position (bump units, along the part's local +X) of the terminal held on the pivot.
    private float anchorLocalX, anchorLocalZ;
    private int anchorTerminal;

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

    /**
     * Local terminal offsets (bump units, along local +X where +X is the placement direction). Two-terminal
     * parts have terminals at 0 and {@code length}; multi-terminal parts will define their own table here.
     */
    private float[][] localTerminals() {
        return new float[][]{{0f, 0f}, {tool.type().length(), 0f}};
    }

    public void select(Tool tool) {
        this.tool = tool;
        this.directions = SnapDirections.forLength(tool.type().length());
        this.anchorTerminal = 0;
    }

    /** Selects a hotbar slot by 0-based index; ignored if out of range. */
    public void selectIndex(int index) {
        Tool[] tools = Tool.values();
        if (index >= 0 && index < tools.length) {
            select(tools[index]);
        }
    }

    /** Cycles which terminal sits on the crosshair pivot (←/→). Direction is unchanged. */
    public void cycleTerminal(int dir) {
        int n = localTerminals().length;
        anchorTerminal = ((anchorTerminal + dir) % n + n) % n;
    }

    /** Nudges the internal heading; placement snaps to nearest viable, the ghost eases to it. */
    public void nudgeDirection(float deltaDeg) {
        dirAngle = ((dirAngle + deltaDeg) % 360f + 360f) % 360f;
    }

    /** Recomputes the hovered part and placement ghost from the crosshair ray; {@code dt} eases the ghost. */
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
        // Discrete placement: the anchored terminal sits on the crosshair, so terminal-0 (the placement
        // origin) is crosshair - (anchored terminal's offset along the discrete direction d).
        int offCol = anchorTerminal == 0 ? 0 : d[0];
        int offRow = anchorTerminal == 0 ? 0 : d[1];
        ghost = new SnapPlacement(tool.type(), anchorCol - offCol, anchorRow - offRow,
                anchorLayer, d[0], d[1], false, Double.NaN);
        ghostValid = board.canPlace(ghost);

        // Ease drawn heading toward the snapped direction, and the anchored local offset toward the chosen
        // terminal (so a terminal change slides the part while direction stays fixed).
        float targetAngle = (float) Math.toDegrees(Math.atan2(d[1], d[0]));
        float diff = ((targetAngle - displayedAngle + 540f) % 360f) - 180f;
        float k = Math.min(1f, dt * EASE_RATE);
        displayedAngle = (displayedAngle + diff * k + 360f) % 360f;

        float[] anchoredLocal = localTerminals()[anchorTerminal];
        anchorLocalX += (anchoredLocal[0] - anchorLocalX) * k;
        anchorLocalZ += (anchoredLocal[1] - anchorLocalZ) * k;
    }

    /** The ghost geometry to draw: a body bar plus terminal bumps, all placed relative to the crosshair pivot. */
    public List<OrientedBox> ghostRender() {
        List<OrientedBox> out = new ArrayList<>();
        if (ghost == null) {
            return out;
        }
        float s = SnapSceneGeometry.BUMP_SPACING;
        float pivotX = anchorCol * s, pivotZ = anchorRow * s;
        float rad = displayedAngle * MathUtils.degreesToRadians;
        float cos = MathUtils.cos(rad), sin = MathUtils.sin(rad);

        float[][] terminals = localTerminals();
        float[] tx = new float[terminals.length];
        float[] tz = new float[terminals.length];
        for (int i = 0; i < terminals.length; i++) {
            // Offset of terminal i from the pivoted (anchored) terminal, in local +X, rotated into world.
            float lx = (terminals[i][0] - anchorLocalX) * s;
            float lz = (terminals[i][1] - anchorLocalZ) * s;
            tx[i] = pivotX + lx * cos - lz * sin;
            tz[i] = pivotZ + lx * sin + lz * cos;
        }

        BoxSpec.Category cat = SnapSceneGeometry.partBox(ghost).category();
        float bodyY = SnapSceneGeometry.bodyCenterY(anchorLayer);
        // Body spans terminal 0..1 (a straight bar); multi-terminal shapes will emit more/other boxes here.
        float bodyLen = tool.type().length() * s + SnapSceneGeometry.COMPONENT_FOOTPRINT;
        out.add(new OrientedBox((tx[0] + tx[1]) / 2f, bodyY, (tz[0] + tz[1]) / 2f,
                bodyLen, SnapSceneGeometry.COMPONENT_HEIGHT, SnapSceneGeometry.COMPONENT_FOOTPRINT,
                displayedAngle, cat, false));

        float bumpY = SnapSceneGeometry.bumpBottomY(anchorLayer + 1) + SnapSceneGeometry.BUMP_HEIGHT / 2f;
        float w = SnapSceneGeometry.BUMP_WIDTH, h = SnapSceneGeometry.BUMP_HEIGHT;
        for (int i = 0; i < terminals.length; i++) {
            out.add(new OrientedBox(tx[i], bumpY, tz[i], w, h, w, 0f, BoxSpec.Category.BUMP, i == anchorTerminal));
        }
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
