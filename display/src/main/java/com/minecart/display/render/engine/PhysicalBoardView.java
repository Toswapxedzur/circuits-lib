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

    private final EngineRenderer engine = new EngineRenderer();
    private final ModelLoader loader = new ModelLoader();
    private final List<Placed> placed = new ArrayList<>();
    private final List<EngineRenderer.DynamicEntity> ents = new ArrayList<>(); // parallel to placed (render entities)
    // Populated on the server thread (buildCircuit), read on the render thread (glow) — concurrent-safe.
    private final java.util.Map<Integer, com.minecart.logic.CircuitEdge> deviceEdge = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean built;
    private boolean hasBase;

    // The board's DISCRETE socket grid — parts anchor here (this is Snap Circuits, not free continuous placement).
    // Sockets sit at world (col*PITCH, boardTopY, row*PITCH) for col∈[0,boardCols), row∈[0,boardRows), matching the
    // studs {@link SnapBaseBoard} draws. Set by {@link #setBaseBoard}.
    public static final float PITCH = SnapBaseBoard.PITCH; // 12 — the physical board's stud spacing
    private int boardCols, boardRows;
    private float boardTopY;
    private static final float ON_SOCKET_EPS2 = 1f; // (1u)² — a terminal this close to a grid point sits ON it

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
        boardCols = cols;
        boardRows = rows;
        boardTopY = topY;
        hasBase = true;
        rebuild();
    }

    /** The nearest in-bounds board socket to world point {@code p} (x,z), or {@code null} if the nearest grid point
     *  is off the board. Sockets are at {@code (col*PITCH, boardTopY, row*PITCH)}. */
    private Vector3 nearestSocket(Vector3 p) {
        int col = Math.round(p.x / PITCH);
        int row = Math.round(p.z / PITCH);
        if (col < 0 || col >= boardCols || row < 0 || row >= boardRows) {
            return null;
        }
        return new Vector3(col * PITCH, boardTopY, row * PITCH);
    }

    /** True if world point {@code w} sits ON an in-bounds board socket (within {@link #ON_SOCKET_EPS2}). */
    private boolean onSocket(Vector3 w) {
        Vector3 s = nearestSocket(w);
        return s != null && (s.x - w.x) * (s.x - w.x) + (s.z - w.z) * (s.z - w.z) <= ON_SOCKET_EPS2;
    }

    /** Commits a part at a world transform (assumes {@link #canPlace} was checked). Rebuilds the render scene. */
    public void place(String modelId, Matrix4 transform) {
        placed.add(new Placed(modelId, new Matrix4(transform)));
        rebuild();
    }

    /** One saved placement: model id + its 16-float world matrix. */
    private static final class SaveEntry { String id; float[] m; }

    /** Persists the placements to {@code f} as JSON (a side-file per world — independent of the grid save path). */
    public void save(com.badlogic.gdx.files.FileHandle f) {
        List<SaveEntry> list = new ArrayList<>(placed.size());
        for (Placed p : placed) {
            SaveEntry s = new SaveEntry();
            s.id = p.modelId();
            s.m = p.transform().val.clone();
            list.add(s);
        }
        f.writeString(new com.google.gson.Gson().toJson(list), false);
    }

    /** Restores placements from {@code f} (if it exists), rebuilds the scene. Returns the count loaded. */
    public int load(com.badlogic.gdx.files.FileHandle f) {
        if (!f.exists()) {
            return 0;
        }
        SaveEntry[] arr = new com.google.gson.Gson().fromJson(f.readString(), SaveEntry[].class);
        if (arr == null) {
            return 0;
        }
        placed.clear();
        for (SaveEntry s : arr) {
            if (s == null || s.id == null || s.m == null || s.m.length != 16) {
                continue;
            }
            Matrix4 mm = new Matrix4();
            System.arraycopy(s.m, 0, mm.val, 0, 16);
            placed.add(new Placed(s.id, mm));
        }
        rebuild();
        return placed.size();
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
            if (kind(p.modelId()) == 'l') {
                e.bodyTint = LED_BODY; // a red LED body (the bulb reads red even before it's lit)
            }
            engine.addEntity(e);
            ents.add(e);
        }
        engine.build();
        built = hasBase || !placed.isEmpty();
    }

    private static final com.badlogic.gdx.graphics.Color LED_BODY = new com.badlogic.gdx.graphics.Color(1f, 0.35f, 0.32f, 1f);
    // Glow colours: a resistor "heats up" warm-orange; an LED lights red; a lamp glows bright warm-white.
    private static final com.badlogic.gdx.graphics.Color GLOW_HEAT = new com.badlogic.gdx.graphics.Color(1f, 0.55f, 0.2f, 1f);
    private static final com.badlogic.gdx.graphics.Color GLOW_LED = new com.badlogic.gdx.graphics.Color(1f, 0.15f, 0.1f, 1f);
    private static final com.badlogic.gdx.graphics.Color GLOW_LAMP = new com.badlogic.gdx.graphics.Color(1f, 0.92f, 0.7f, 1f);
    private final com.badlogic.gdx.graphics.Color glowTmp = new com.badlogic.gdx.graphics.Color();

    /** Reads each device's solved current and makes the part EMIT light proportional to it — so a live circuit
     *  glows in-world (a resistor "heats up", an LED lights its colour), not just in the HUD. Called per frame. */
    private void updateElectricalGlow() {
        for (int i = 0; i < ents.size(); i++) {
            EngineRenderer.DynamicEntity e = ents.get(i);
            com.minecart.logic.CircuitEdge edge = deviceEdge.get(i);
            double cur = edge == null ? 0.0 : Math.abs(edge.getCurrent().getValue());
            if (cur > 1e-4) {
                char k = kind(placed.get(i).modelId());
                boolean emitter = (k == 'l' || k == 'p'); // LED / lamp are light SOURCES (bright, wide)
                com.badlogic.gdx.graphics.Color base = k == 'l' ? GLOW_LED : k == 'p' ? GLOW_LAMP : GLOW_HEAT;
                float b = emitter ? (float) Math.min(1.0, 0.9 + cur * 4.0) : (float) Math.min(1.0, 0.5 + cur * 8.0);
                e.light = glowTmp.set(base.r * b, base.g * b, base.b * b, 1f);
                e.lightRange = emitter ? (float) Math.min(110.0, 60.0 + cur * 800.0)
                        : (float) Math.min(90.0, 30.0 + cur * 600.0);
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
            if (k == 'r' || k == 'b' || k == 'l' || k == 'p' || k == 'c') {
                Vector3[] t = terminals(p);
                if (t == null) continue;
                com.minecart.logic.CircuitNode a = field.at(t[0]), bb = field.at(t[1]);
                if (a == bb) continue; // terminals shorted onto one net
                switch (k) {
                    case 'r' -> deviceEdge.put(i, world.connect(com.minecart.registry.AllComponents.RESISTOR, a, bb,
                            new com.minecart.variant.Informations.ResistorInfo(100.0)));
                    case 'l' -> // LED: a true DIODE — forward ~220Ω limits current + lights; reverse ~1MΩ blocks
                            deviceEdge.put(i, world.connect(com.minecart.registry.AllComponents.DIODE, a, bb,
                                    new com.minecart.variant.Informations.DiodeInfo(220.0, 1.0e6)));
                    case 'p' -> // lamp: a low-resistance heating element (bright warm glow)
                            deviceEdge.put(i, world.connect(com.minecart.registry.AllComponents.RESISTOR, a, bb,
                                    new com.minecart.variant.Informations.ResistorInfo(50.0)));
                    case 'c' -> // capacitor: charges then blocks DC (transient current)
                            deviceEdge.put(i, world.connect(com.minecart.registry.AllComponents.CAPACITOR, a, bb,
                                    new com.minecart.variant.Informations.CapacitorInfo(1.0e-3, 1.0)));
                    default -> {
                        lastBattery = world.connect(com.minecart.registry.AllComponents.BATTERY, a, bb,
                                new com.minecart.variant.Informations.BatteryInfo(5.0, 0.01));
                        deviceEdge.put(i, lastBattery);
                    }
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
        if (modelId.startsWith("led")) return 'l';
        if (modelId.startsWith("lamp")) return 'p';
        if (modelId.startsWith("capacitor")) return 'c';
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

        private static String key(Vector3 p) { // round to 2u; Y DROPPED — a board post is one vertical conductor, so
            return Math.round(p.x / 2f) + "," + Math.round(p.z / 2f); // stacked parts sharing an (x,z) post are one node
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
     * GRID SNAP: this is Snap Circuits — parts anchor to the board's DISCRETE socket grid, not to free continuous
     * positions. Given a candidate transform for {@code modelId}, snap its yaw to the nearest 90° (so its two end
     * terminals run along a grid axis) and translate so one terminal lands EXACTLY on the nearest in-bounds board
     * socket; because the terminal span (±½·PITCH) equals one grid step, the other terminal auto-lands on the
     * adjacent socket. Parts thus connect by SHARING a socket (coincident terminals → one circuit node). Returns
     * {@code candidate} unchanged only when there's no board or the part has no connectors.
     */
    public Matrix4 snap(String modelId, Matrix4 candidate) {
        ComponentModel m = loader.model(modelId);
        if (m.connectors.isEmpty() || !hasBase) {
            return candidate;
        }
        // Snap yaw to the nearest quarter turn so the terminals align to the grid axes.
        float yawDeg = candidate.getRotation(new com.badlogic.gdx.math.Quaternion(), true).getYaw();
        float snapYaw = Math.round(yawDeg / 90f) * 90f;
        Vector3 t = candidate.getTranslation(new Vector3());
        // Keep the candidate's HEIGHT: ground placement passes y=board level (FLAT — the default, no auto-stacking);
        // snapToPort passes an elevated y so a deliberately-targeted stack lands on top. Parts do NOT auto-climb.
        Matrix4 base = new Matrix4().setToTranslation(t.x, t.y, t.z)
                .rotate(0f, 1f, 0f, snapYaw); // grid-aligned, at the candidate's height
        // Land the first terminal on its nearest socket; the rest follow by construction.
        Vector3 p0 = new Vector3(m.connectors.get(0).local()).mul(base);
        Vector3 s = nearestSocket(p0);
        if (s == null) {
            return base; // off the board → leave it (canPlace will reject → red ghost)
        }
        Vector3 d = new Vector3(s.x - p0.x, 0f, s.z - p0.z);
        return new Matrix4().setToTranslation(d).mul(base); // x-z snapped, height preserved
    }

    /**
     * PORT-ALIAS TARGETING: casts the crosshair {@code ray} at the placed parts and resolves to the PORT (stud) it's
     * aiming at. The nearest part the ray enters wins; within it, the port nearest the entry point is the target —
     * i.e. a part's face is partitioned per-stud, and each stud's region <b>aliases</b> that stud's port (owner's
     * "portion of a face = alias of a port"). Only a hit on the part's TOP face counts (so aiming PAST a part at the
     * ground behind it doesn't grab it — that stays a flat board placement). Returns the target stud's world position
     * (x, that part's TOP y, z) so {@link #snapToPort} stacks the new part on top. {@code null} → the ray hit no
     * part's top, so the caller falls back to the board plane. This is what lets you deliberately build UPWARD.
     */
    public Vector3 pickTarget(com.badlogic.gdx.math.collision.Ray ray) {
        Placed best = null;
        float bestDist = Float.MAX_VALUE, bestTop = boardTopY;
        Vector3 hit = new Vector3(), bestHit = new Vector3();
        for (Placed p : placed) {
            ComponentModel pm = loader.model(p.modelId());
            if (pm.collision == null) {
                continue;
            }
            float[] ab = worldAabb(pm.collision, p.transform());
            com.badlogic.gdx.math.collision.BoundingBox bb = new com.badlogic.gdx.math.collision.BoundingBox(
                    new Vector3(ab[0], ab[1], ab[2]), new Vector3(ab[3], ab[4], ab[5]));
            if (com.badlogic.gdx.math.Intersector.intersectRayBounds(ray, bb, hit) && hit.y >= ab[4] - 1.5f) {
                float d = ray.origin.dst2(hit); // TOP-face hit only (entry y at the box top)
                if (d < bestDist) {
                    bestDist = d;
                    best = p;
                    bestHit.set(hit);
                    bestTop = ab[4];
                }
            }
        }
        if (best == null) {
            return null;
        }
        ComponentModel pm = loader.model(best.modelId());
        Vector3 nearest = null;
        float nd = Float.MAX_VALUE;
        for (ComponentModel.Connector c : pm.connectors) {
            Vector3 w = new Vector3(c.local()).mul(best.transform());
            float d = (w.x - bestHit.x) * (w.x - bestHit.x) + (w.z - bestHit.z) * (w.z - bestHit.z);
            if (d < nd) {
                nd = d;
                nearest = w;
            }
        }
        return nearest == null ? null : new Vector3(nearest.x, bestTop, nearest.z); // stud x-z at the part's TOP
    }

    /** Places {@code modelId} so its FIRST connector lands on the targeted port {@code port} (x, top-y, z) at the
     *  given yaw — i.e. ON TOP of the aimed part — then grid-aligns via {@link #snap} (which preserves the height). */
    public Matrix4 snapToPort(String modelId, Vector3 port, float yawDeg) {
        ComponentModel m = loader.model(modelId);
        float ex = m.connectors.isEmpty() ? 0f : -m.connectors.get(0).local().x; // connector[0] sits at local −ex
        Matrix4 rot = new Matrix4().setToRotation(Vector3.Y, yawDeg);
        Vector3 off = new Vector3(ex, 0f, 0f).rot(rot); // where connector[0] ends up relative to the part centre
        Matrix4 candidate = new Matrix4()
                .setToTranslation(port.x + off.x, port.y, port.z + off.z).rotate(0f, 1f, 0f, yawDeg);
        return snap(modelId, candidate);
    }

    private static final float MATE_EPS2 = 4f; // (2u)² — connectors this close count as coincident (a shared stud)

    /**
     * 3D validity: every terminal must land on an in-bounds board socket (x-z grid), AND the part's 3D box must not
     * clash with a placed part's. It's a TRUE 3D test — parts at DIFFERENT heights (a deliberate stack) never clash
     * (only their under-stud peg touches, within {@link #overlap}'s Y tolerance). Among parts at the SAME height, an
     * inline JOINT is allowed (they share a stud and overlap ≤ one pitch in each ground axis — end-to-end / corner),
     * but a part COVERING another (overlap > pitch) is rejected: to place there you aim at the stud and stack.
     */
    public boolean canPlace(String modelId, Matrix4 transform) {
        ComponentModel m = loader.model(modelId);
        // ANCHORING: on a board, every terminal must land ON a socket (Snap Circuits — no floating in free space).
        if (hasBase && !m.connectors.isEmpty()) {
            for (ComponentModel.Connector c : m.connectors) {
                if (!onSocket(new Vector3(c.local()).mul(transform))) {
                    return false;
                }
            }
        }
        if (m.collision == null) {
            return true;
        }
        float[] a = worldAabb(m.collision, transform);
        List<Vector3> mine = candidateConnectors(m, transform);
        for (Placed p : placed) {
            ComponentModel pm = loader.model(p.modelId());
            if (pm.collision == null) {
                continue;
            }
            float[] b = worldAabb(pm.collision, p.transform());
            if (overlap(a, b)) { // a REAL 3D clash (same height — stacks clear via the peg tolerance)
                if (!sharesConnector(mine, pm, p.transform()) || overlapExceedsPitch(a, b)) {
                    return false; // covering / crossing without a shared stud → invalid at this level
                }
            }
        }
        return true;
    }

    /** True if AABBs overlap by more than one grid pitch in either ground axis (x/z) — one part covers the other,
     *  rather than just meeting it end-to-end / at a corner. */
    private static boolean overlapExceedsPitch(float[] a, float[] b) {
        float ox = Math.min(a[3], b[3]) - Math.max(a[0], b[0]);
        float oz = Math.min(a[5], b[5]) - Math.max(a[2], b[2]);
        return ox > PITCH + 0.5f || oz > PITCH + 0.5f;
    }

    /** World positions of {@code m}'s connectors under {@code world}. */
    private List<Vector3> candidateConnectors(ComponentModel m, Matrix4 world) {
        List<Vector3> out = new ArrayList<>(m.connectors.size());
        for (ComponentModel.Connector c : m.connectors) {
            out.add(new Vector3(c.local()).mul(world));
        }
        return out;
    }

    /** True if any of {@code mine} coincides (within {@link #MATE_EPS2}, x-z) with a connector of placed part {@code pm}. */
    private boolean sharesConnector(List<Vector3> mine, ComponentModel pm, Matrix4 pWorld) {
        for (ComponentModel.Connector c : pm.connectors) {
            Vector3 w = new Vector3(c.local()).mul(pWorld);
            for (Vector3 g : mine) {
                float dx = g.x - w.x, dz = g.z - w.z;
                if (dx * dx + dz * dz <= MATE_EPS2) {
                    return true;
                }
            }
        }
        return false;
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
        float eps = 0.5f;    // x/z: touching faces allowed
        float epsY = 1.5f;   // y: the under-stud PEG interlock at a stacked joint (~1u) is a connection, not a clash
        return a[0] < b[3] - eps && a[3] > b[0] + eps
                && a[1] < b[4] - epsY && a[4] > b[1] + epsY
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
