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
    private final List<EngineRenderer.DynamicEntity> ents = new ArrayList<>(); // parallel to placed (render entities)
    // Populated on the server thread (buildCircuit), read on the render thread (glow) — concurrent-safe.
    private final java.util.Map<Integer, com.minecart.logic.CircuitEdge> deviceEdge = new java.util.concurrent.ConcurrentHashMap<>();
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
        ents.clear();
        for (Placed p : placed) {
            EngineRenderer.DynamicEntity e = new EngineRenderer.DynamicEntity(loader.model(p.modelId()));
            e.pose(p.transform());
            engine.addEntity(e);
            ents.add(e);
        }
        engine.build();
        built = hasBase || !placed.isEmpty();
    }

    // Warm colour a device glows when carrying current; brightness + range scale with |current|.
    private static final com.badlogic.gdx.graphics.Color GLOW = new com.badlogic.gdx.graphics.Color(1f, 0.55f, 0.2f, 1f);
    private final com.badlogic.gdx.graphics.Color glowTmp = new com.badlogic.gdx.graphics.Color();

    /** Reads each device's solved current and makes the part EMIT light proportional to it — so a live circuit
     *  glows in-world (a resistor "heats up"), not just in the HUD. Called each frame before rendering. */
    private void updateElectricalGlow() {
        for (int i = 0; i < ents.size(); i++) {
            EngineRenderer.DynamicEntity e = ents.get(i);
            com.minecart.logic.CircuitEdge edge = deviceEdge.get(i);
            double cur = edge == null ? 0.0 : Math.abs(edge.getCurrent().getValue());
            if (cur > 1e-4) {
                float b = (float) Math.min(1.0, 0.5 + cur * 8.0); // brightness ramps with current
                e.light = glowTmp.set(GLOW.r * b, GLOW.g * b, GLOW.b * b, 1f);
                e.lightRange = (float) Math.min(90.0, 30.0 + cur * 600.0);
            } else {
                e.light = null;
                e.lightRange = 0f;
            }
        }
    }

    private com.minecart.logic.CircuitEdge lastBattery; // captured to read solved current (a live-circuit proof)

    /**
     * Rebuilds the world's ELECTRICAL circuit from the physical placements — the {@code ConnectorField} unions
     * connectors that COINCIDE in world space into shared {@link com.minecart.logic.CircuitNode}s (replacing the
     * grid's post-sharing), then wires union their terminals and devices attach elements between nodes. Reuses the
     * exact core solver. Call after every place/remove.
     */
    public void buildCircuit(com.minecart.logic.ServerWorld world) {
        for (com.minecart.foundation.Circuit c : new ArrayList<>(world.getCircuits())) {
            world.removeCircuit(c);
        }
        lastBattery = null;
        deviceEdge.clear();
        ConnectorField field = new ConnectorField(world);
        // Pass 1: wires union their two terminals (so a wire run collapses to one node).
        for (Placed p : placed) {
            if (kind(p.modelId()) == 'w') {
                Vector3[] t = terminals(p);
                if (t != null) field.union(t[0], t[1]);
            }
        }
        // Pass 2: devices attach their element between the (now-merged) coincident-connector nodes.
        for (int i = 0; i < placed.size(); i++) {
            Placed p = placed.get(i);
            char k = kind(p.modelId());
            if (k == 'r' || k == 'b') {
                Vector3[] t = terminals(p);
                if (t == null) continue;
                com.minecart.logic.CircuitNode a = field.at(t[0]), bb = field.at(t[1]);
                if (a == bb) continue; // terminals shorted onto one net
                if (k == 'r') {
                    deviceEdge.put(i, world.connect(com.minecart.registry.AllComponents.RESISTOR, a, bb,
                            new com.minecart.variant.Informations.ResistorInfo(100.0)));
                } else {
                    lastBattery = world.connect(com.minecart.registry.AllComponents.BATTERY, a, bb,
                            new com.minecart.variant.Informations.BatteryInfo(5.0, 0.01));
                    deviceEdge.put(i, lastBattery);
                }
            }
        }
        if (DBG) {
            com.badlogic.gdx.Gdx.app.log("PHYS-CIRCUIT", "placed=" + placed.size() + " circuits="
                    + world.getCircuits().size() + " battery=" + (lastBattery != null)
                    + (lastBattery != null ? " I0=" + lastBattery.getCurrent().getValue() : "")
                    + " termsBat=" + java.util.Arrays.toString(termKeys("battery_cell"))
                    + " termsRes=" + java.util.Arrays.toString(termKeys("resistor")));
        }
    }

    private static final boolean DBG = "1".equals(System.getProperty("snap.phystest"));
    private String[] termKeys(String modelId) {
        for (Placed p : placed) {
            if (p.modelId().equals(modelId)) {
                Vector3[] t = terminals(p);
                if (t != null) return new String[]{ConnectorField.key(t[0]), ConnectorField.key(t[1])};
            }
        }
        return new String[0];
    }

    /** The most recent battery's solved current magnitude (amps) — a live-circuit readout; 0 if none/unsolved. */
    public double batteryCurrent() {
        return lastBattery == null ? 0.0 : Math.abs(lastBattery.getCurrent().getValue());
    }

    private static char kind(String modelId) {
        if (modelId.startsWith("wire")) return 'w';
        if (modelId.startsWith("resistor")) return 'r';
        if (modelId.startsWith("battery")) return 'b';
        return '.';
    }

    /** A placement's two terminal world positions (index 0 / 1), or null if it lacks both. */
    private Vector3[] terminals(Placed p) {
        ComponentModel m = loader.model(p.modelId());
        Vector3 t0 = null, t1 = null;
        for (ComponentModel.Connector c : m.connectors) {
            Vector3 w = new Vector3(c.local()).mul(p.transform());
            if (c.terminal() == 0) t0 = w;
            else if (c.terminal() == 1) t1 = w;
        }
        return (t0 != null && t1 != null) ? new Vector3[]{t0, t1} : null;
    }

    /** Union-find over connectors that COINCIDE in world space → one shared circuit node each (grid PostGrid's
     *  role, but keyed by quantised world position since snapping mates connectors exactly). */
    private static final class ConnectorField {
        private final com.minecart.logic.ServerWorld world;
        private final java.util.Map<String, Integer> keyId = new java.util.HashMap<>();
        private final List<Integer> parent = new ArrayList<>();
        private final java.util.Map<Integer, com.minecart.logic.CircuitNode> node = new java.util.HashMap<>();

        ConnectorField(com.minecart.logic.ServerWorld world) {
            this.world = world;
        }

        private static String key(Vector3 p) { // round to 2u — mated connectors coincide, distinct ones don't
            return Math.round(p.x / 2f) + "," + Math.round(p.y / 2f) + "," + Math.round(p.z / 2f);
        }

        private int id(Vector3 p) {
            return keyId.computeIfAbsent(key(p), k -> {
                parent.add(parent.size());
                return parent.size() - 1;
            });
        }

        private int find(int i) {
            while (parent.get(i) != i) {
                parent.set(i, parent.get(parent.get(i)));
                i = parent.get(i);
            }
            return i;
        }

        void union(Vector3 a, Vector3 b) {
            int ra = find(id(a)), rb = find(id(b));
            if (ra != rb) parent.set(ra, rb);
        }

        com.minecart.logic.CircuitNode at(Vector3 p) {
            return node.computeIfAbsent(find(id(p)),
                    r -> world.createNode(com.minecart.registry.AllComponents.CONNECTION));
        }
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
        updateElectricalGlow(); // live current → per-part emission (glow) before the lighting pass
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
