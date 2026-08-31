package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

import java.util.ArrayList;
import java.util.List;

/**
 * The <b>physical free-placement</b> board (the Minecraft-grid replacement): parts live at continuous world
 * transforms and connect where their {@link ComponentModel.Connector connectors} physically coincide — no lattice.
 * A part is positioned freely; when one of its connectors comes near a compatible connector already on the board
 * it MAGNETICALLY snaps to mate exactly. Placement is rejected when the part's collision box overlaps another.
 *
 * <p>This is the display-side seam (like {@link EngineBoardView}): it holds the placements + the engine renderer,
 * and exposes snap / collision / connector queries for the editor. Electrical connectivity (a {@code ConnectorField}
 * that unions coincident connectors into circuit nodes) is layered on top of {@link #connectorsWorld()} next.
 */
public final class PhysicalBoardView implements Disposable {

    /** One placed part: its model id + a continuous world transform. */
    public record Placed(String modelId, Matrix4 transform) {}

    /** A connector in world space (for snapping + connectivity): position, mating axis, terminal, male=stud. */
    public record WorldConnector(Vector3 pos, Vector3 axis, int terminal, boolean male, int placementIndex) {}

    private static final float SNAP_RADIUS = 12f; // world units within which a ghost connector snaps to a target

    private final EngineRenderer engine = new EngineRenderer();
    private final ModelLoader loader = new ModelLoader();
    private final List<Placed> placed = new ArrayList<>();
    private boolean built;
    private boolean hasBase;

    // Ghost easing state (reused from the eased translucent preview).
    private static final float GHOST_EASE = 12f, GHOST_ALPHA = 0.5f;
    private final Vector3 gTgtPos = new Vector3(), gDispPos = new Vector3();
    private final com.badlogic.gdx.math.Quaternion gTgtRot = new com.badlogic.gdx.math.Quaternion();
    private final com.badlogic.gdx.math.Quaternion gDispRot = new com.badlogic.gdx.math.Quaternion();
    private final Matrix4 gDisplayed = new Matrix4();
    private String gModelId;
    private boolean gPresent, gValid;

    private final Vector3 tmp = new Vector3(), tmp2 = new Vector3();

    public PhysicalBoardView() {
        for (String id : com.minecart.display.snap.SnapModelBridge.allModelIds()) {
            engine.addGhostModel(id, loader.model(id));
        }
    }

    public void setLightDir(float x, float y, float z) {
        engine.setLightDir(x, y, z);
    }

    /** Adds the base board (tiled, top at {@code topY}); it stays static across rebuilds. Builds the scene so the
     *  board (and the ghost models' atlas) render immediately, before any part is placed. */
    public void setBaseBoard(int cols, int rows, float topY) {
        engine.addStatic(SnapBaseBoard.build(cols, rows, topY));
        hasBase = true;
        rebuild();
    }

    /** Commits a part at a world transform (assumes {@link #canPlace} was checked). Rebuilds the render scene. */
    public void place(String modelId, Matrix4 transform) {
        placed.add(new Placed(modelId, new Matrix4(transform)));
        rebuild();
    }

    /** Removes the placement nearest {@code worldPoint} within {@code radius}; returns true if one was removed. */
    public boolean removeNear(Vector3 worldPoint, float radius) {
        int best = -1;
        float bestD = radius * radius;
        for (int i = 0; i < placed.size(); i++) {
            placed.get(i).transform().getTranslation(tmp);
            float d = tmp.dst2(worldPoint);
            if (d < bestD) { bestD = d; best = i; }
        }
        if (best >= 0) {
            placed.remove(best);
            rebuild();
            return true;
        }
        return false;
    }

    private void rebuild() {
        engine.clearEntities();
        for (Placed p : placed) {
            EngineRenderer.DynamicEntity e = new EngineRenderer.DynamicEntity(loader.model(p.modelId()));
            e.pose(p.transform());
            engine.addEntity(e);
        }
        engine.build();
        built = hasBase || !placed.isEmpty();
    }

    /** Every placed part's connectors in world space (for snapping + electrical connectivity). */
    public List<WorldConnector> connectorsWorld() {
        List<WorldConnector> out = new ArrayList<>();
        for (int i = 0; i < placed.size(); i++) {
            Placed p = placed.get(i);
            appendConnectors(loader.model(p.modelId()), p.transform(), i, out);
        }
        return out;
    }

    private void appendConnectors(ComponentModel m, Matrix4 world, int idx, List<WorldConnector> out) {
        for (ComponentModel.Connector c : m.connectors) {
            Vector3 pos = new Vector3(c.local()).mul(world);
            Vector3 axis = new Vector3(c.axis()).rot(world).nor();
            out.add(new WorldConnector(pos, axis, c.terminal(), c.male(), idx));
        }
    }

    /**
     * MAGNETIC SNAP with ROTATION-ALIGN: given a candidate transform for {@code modelId}, if one of its connectors
     * is within {@link #SNAP_RADIUS} of a compatible placed connector, returns a transform that ROTATES the part so
     * that connector's outward axis anti-aligns the target's (they face each other) AND translates so the pair
     * coincides exactly — a clean end-to-end mate at any angle. Otherwise returns {@code candidate} unchanged.
     */
    public Matrix4 snap(String modelId, Matrix4 candidate) {
        ComponentModel m = loader.model(modelId);
        if (m.connectors.isEmpty()) {
            return candidate;
        }
        List<WorldConnector> targets = connectorsWorld();
        if (targets.isEmpty()) {
            return candidate;
        }
        float bestD = SNAP_RADIUS * SNAP_RADIUS;
        Matrix4 best = null;
        for (ComponentModel.Connector c : m.connectors) {
            Vector3 gp = tmp.set(c.local()).mul(candidate); // ghost connector current world pos
            for (WorldConnector t : targets) {
                float d = gp.dst2(t.pos());
                if (d < bestD) {
                    bestD = d;
                    best = alignConnector(c, t);
                }
            }
        }
        return best != null ? best : candidate;
    }

    /** Builds the transform that mates ghost connector {@code c} onto placed connector {@code t}: yaw so c's
     *  outward axis anti-aligns t's (face-to-face), then translate so c lands exactly on t. */
    private Matrix4 alignConnector(ComponentModel.Connector c, WorldConnector t) {
        // Desired WORLD angle of c's outward axis = angle of (−t.axis); c's LOCAL outward angle = atan2(z, x).
        float wantAng = (float) Math.atan2(-t.axis().z, -t.axis().x);
        float localAng = (float) Math.atan2(c.axis().z, c.axis().x);
        float yawRad = wantAng - localAng;
        Matrix4 r = new Matrix4().setToRotationRad(0f, 1f, 0f, yawRad);
        Vector3 rotated = new Vector3(c.local()).rot(r);          // c's pos after rotation (about origin)
        Vector3 trans = new Vector3(t.pos()).sub(rotated);        // translate so rotated + trans = t.pos
        return new Matrix4().setToTranslation(trans).mul(r);      // world = T · R
    }

    /** True if {@code modelId} at {@code transform} does NOT overlap any placed part (world-AABB test). */
    public boolean canPlace(String modelId, Matrix4 transform) {
        ComponentModel m = loader.model(modelId);
        if (m.collision == null) {
            return true;
        }
        float[] a = worldAabb(m.collision, transform);
        for (Placed p : placed) {
            ComponentModel pm = loader.model(p.modelId());
            if (pm.collision == null) {
                continue;
            }
            float[] b = worldAabb(pm.collision, p.transform());
            if (overlap(a, b)) {
                return false;
            }
        }
        return true;
    }

    /** World-space AABB {minx,miny,minz,maxx,maxy,maxz} of a collision box under {@code world} (8-corner bound). */
    private float[] worldAabb(ComponentModel.Collision c, Matrix4 world) {
        float minx = Float.MAX_VALUE, miny = minx, minz = minx, maxx = -minx, maxy = -minx, maxz = -minx;
        for (int i = 0; i < 8; i++) {
            tmp2.set(c.cx() + ((i & 1) == 0 ? -c.hx() : c.hx()),
                    c.cy() + ((i & 2) == 0 ? -c.hy() : c.hy()),
                    c.cz() + ((i & 4) == 0 ? -c.hz() : c.hz())).mul(world);
            minx = Math.min(minx, tmp2.x); maxx = Math.max(maxx, tmp2.x);
            miny = Math.min(miny, tmp2.y); maxy = Math.max(maxy, tmp2.y);
            minz = Math.min(minz, tmp2.z); maxz = Math.max(maxz, tmp2.z);
        }
        return new float[]{minx, miny, minz, maxx, maxy, maxz};
    }

    private static boolean overlap(float[] a, float[] b) {
        float eps = 0.5f; // touching (shared connector face) is allowed, not counted as overlap
        return a[0] < b[3] - eps && a[3] > b[0] + eps
                && a[1] < b[4] - eps && a[4] > b[1] + eps
                && a[2] < b[5] - eps && a[5] > b[2] + eps;
    }

    /** Sets the eased translucent ghost (real model) for this frame; {@code present=false} hides it. */
    public void setGhost(boolean present, String modelId, Matrix4 truePose, boolean valid, float dt) {
        if (!present || modelId == null) {
            gPresent = false;
            return;
        }
        boolean changed = !modelId.equals(gModelId);
        gModelId = modelId;
        gValid = valid;
        truePose.getTranslation(gTgtPos);
        truePose.getRotation(gTgtRot, true);
        if (!gPresent || changed) {
            gDispPos.set(gTgtPos);
            gDispRot.set(gTgtRot);
        } else {
            float k = Math.min(1f, dt * GHOST_EASE);
            gDispPos.lerp(gTgtPos, k);
            gDispRot.slerp(gTgtRot, k);
        }
        gDisplayed.set(gDispPos, gDispRot);
        gPresent = true;
    }

    public void render(Camera cam) {
        if (!built) {
            return;
        }
        engine.render(cam);
        if (gPresent) {
            if (gValid) {
                engine.drawGhost(cam, gModelId, gDisplayed, 1f, 1f, 1f, GHOST_ALPHA);
            } else {
                engine.drawGhost(cam, gModelId, gDisplayed, 1f, 0.45f, 0.4f, GHOST_ALPHA);
            }
        }
    }

    public List<Placed> placements() {
        return placed;
    }

    @Override
    public void dispose() {
        engine.dispose();
    }
}
