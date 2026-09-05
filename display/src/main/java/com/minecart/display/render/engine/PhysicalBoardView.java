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
    // Interactive sub-part state: placementIndex → the driven channel value (switch position, dial angle…). Written
    // on the render thread (drag), read on the server thread (buildCircuit) — concurrent-safe.
    private final java.util.Map<Integer, Float> subState = new java.util.concurrent.ConcurrentHashMap<>();
    // Grab reference (drag-handle): the aim projection + channel value AT grab time, so the grabbed point stays under
    // the cursor as it moves (relative drag, not absolute snap).
    private float grabProj, grabChannel;
    private boolean grabValid;
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

    /** DIAGNOSTIC: is a stud at world {@code s} actually SUPPORTED — resting on the board (y≈topY) or on the TOP of
     *  a placed part directly beneath it — or is it FLOATING in mid-air? {@code exclude} skips one placement (the
     *  part the stud belongs to). */
    public boolean studSupported(Vector3 s, int exclude) {
        if (Math.abs(s.y - boardTopY) < 1.5f && nearestSocket(s) != null) {
            return true; // resting on the board
        }
        for (int i = 0; i < placed.size(); i++) {
            if (i == exclude) continue;
            ComponentModel pm = loader.model(placed.get(i).modelId());
            if (pm.collision == null) continue;
            float[] b = worldAabb(pm.collision, placed.get(i).transform());
            boolean coversXZ = s.x > b[0] - 1f && s.x < b[3] + 1f && s.z > b[2] - 1f && s.z < b[5] + 1f;
            if (coversXZ && Math.abs(b[4] - s.y) < 1.6f) {
                return true; // resting on this part's top face
            }
        }
        return false;
    }

    /** Commits a part at a world transform (assumes {@link #canPlace} was checked). Rebuilds the render scene. */
    public void place(String modelId, Matrix4 transform) {
        placed.add(new Placed(modelId, new Matrix4(transform)));
        rebuild();
    }

    /** Removes every placement (test/reset helper). */
    public void clearAll() {
        placed.clear();
        deviceEdge.clear();
        subState.clear();
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
            subState.clear(); // indices shift on remove — reset interactive states (rare, acceptable)
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
        // Pass 1: pure conductors union their two terminals — a wire/tee run always; a switch ONLY when closed.
        for (int i = 0; i < placed.size(); i++) {
            Placed p = placed.get(i);
            char k = kind(p.modelId());
            if (k == 'w' || (k == 's' && switchClosed(i, loader.model(p.modelId())))) {
                Vector3[] t = terminals(p);
                if (t != null) field.union(t[0], t[1]);
            }
        }
        // Pass 2: devices attach their element between the (now-merged) coincident-connector nodes.
        for (int i = 0; i < placed.size(); i++) {
            Placed p = placed.get(i);
            char k = kind(p.modelId());
            if (k == 'r' || k == 'b' || k == 'l' || k == 'p' || k == 'c' || k == 'd') {
                Vector3[] t = terminals(p);
                if (t == null) continue;
                com.minecart.logic.CircuitNode a = field.at(t[0]), bb = field.at(t[1]);
                if (a == bb) continue; // terminals shorted onto one net
                switch (k) {
                    case 'r' -> deviceEdge.put(i, world.connect(com.minecart.registry.AllComponents.RESISTOR, a, bb,
                            new com.minecart.variant.Informations.ResistorInfo(resistanceOhms(i, loader.model(p.modelId())))));
                    case 'l' -> // LED: a true DIODE — forward ~220Ω limits current + lights; reverse ~1MΩ blocks
                            deviceEdge.put(i, world.connect(com.minecart.registry.AllComponents.DIODE, a, bb,
                                    new com.minecart.variant.Informations.DiodeInfo(220.0, 1.0e6)));
                    case 'd' -> // plain diode: one-way conductor (no light)
                            deviceEdge.put(i, world.connect(com.minecart.registry.AllComponents.DIODE, a, bb,
                                    new com.minecart.variant.Informations.DiodeInfo(1.0, 1.0e6)));
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
        return com.minecart.display.snap.SnapModelBridge.kindOf(modelId);
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
        // CONNECTION = STACK: the target stud x-z at the aimed part's TOP, so the new part goes ON TOP at the shared
        // post (real Snap Circuits — you overlap one part under another; the height difference clears the 3D boxes).
        return nearest == null ? null : new Vector3(nearest.x, bestTop, nearest.z);
    }

    /** Places {@code modelId} so its <b>anchor</b> terminal (connector {@code anchorIdx}, mod the count) lands on the
     *  targeted {@code port} (x, level-y, z) at the given yaw. The anchor is grid-snapped to the socket nearest the
     *  port; the part extends per the yaw and the OTHER terminal auto-lands on its socket (spans are pitch multiples).
     *  ←/→ pick which terminal anchors; scroll/R pick the direction. */
    public Matrix4 snapToPort(String modelId, Vector3 port, float yawDeg, int anchorIdx) {
        ComponentModel m = loader.model(modelId);
        float snapYaw = Math.round(yawDeg / 90f) * 90f;
        if (m.connectors.isEmpty() || !hasBase) {
            return new Matrix4().setToTranslation(port.x, port.y, port.z).rotate(0f, 1f, 0f, snapYaw);
        }
        int n = m.connectors.size();
        Vector3 aLocal = new Vector3(m.connectors.get(((anchorIdx % n) + n) % n).local());
        Vector3 rotA = aLocal.rotate(Vector3.Y, snapYaw); // anchor's local offset, rotated (y stays 0)
        Vector3 socket = nearestSocket(new Vector3(port.x, port.y, port.z)); // grid-snap the anchor (x-z)
        float ax = socket != null ? socket.x : port.x;
        float az = socket != null ? socket.z : port.z;
        // centre so the anchor lands exactly on (ax, port.y, az)
        return new Matrix4().setToTranslation(ax - rotA.x, port.y - rotA.y, az - rotA.z)
                .rotate(0f, 1f, 0f, snapYaw);
    }

    /**
     * STRICT 3D validity (real Snap Circuits): every terminal must land on an in-bounds board socket (x-z grid), AND
     * the part's 3D bounding box must NOT intersect any placed part's — no exceptions. Two solid bodies can't share
     * a space at the same height, so a flat same-level "joint" IS a collision and is rejected. To connect, you stack
     * (aim at a stud → the part goes ON TOP at the shared post), which lands at a DIFFERENT height so the 3D boxes
     * clear — only the under-stud peg interlocks, absorbed by {@link #overlap}'s small Y tolerance.
     */
    public boolean canPlace(String modelId, Matrix4 transform) {
        ComponentModel m = loader.model(modelId);
        // ANCHORING + SUPPORT: on a board, every terminal must land ON a socket (x-z grid) AND be SUPPORTED — resting
        // on the board or on a placed part directly beneath it. No cantilevering: a part can't float with one end
        // (or both) hanging in mid-air. So a stacked part must rest fully on what's below, not poke out over nothing.
        if (hasBase && !m.connectors.isEmpty()) {
            for (ComponentModel.Connector c : m.connectors) {
                Vector3 w = new Vector3(c.local()).mul(transform);
                if (!onSocket(w) || !studSupported(w, -1)) {
                    return false;
                }
            }
        }
        if (m.collision == null) {
            return true;
        }
        float[] a = worldAabb(m.collision, transform);
        for (Placed p : placed) {
            ComponentModel pm = loader.model(p.modelId());
            if (pm.collision == null) {
                continue;
            }
            if (overlap(a, worldAabb(pm.collision, p.transform()))) {
                return false; // 3D boxes clash — a body already occupies this space at this height
            }
        }
        return true;
    }

    /** What the cursor is hovering: a placed part ({@code subPart == -1}) or one of its movable SUB-PARTS (the
     *  switch knob, a dial…), plus that element's world AABB for the highlight outline. */
    public record Focus(int placementIndex, int subPart, float[] aabb) {}

    /** Raycast the cursor {@code ray} against every placed part's base box AND each movable sub-part's box; returns
     *  the NEAREST one entered (so the little knob on top wins over the base under it), or null if nothing is hit.
     *  This is the per-face hitbox resolution — sub-parts are separate pick targets from the base. */
    public Focus focusAt(com.badlogic.gdx.math.collision.Ray ray) {
        Focus best = null;
        float bestDist = Float.MAX_VALUE;
        Vector3 hit = new Vector3();
        for (int i = 0; i < placed.size(); i++) {
            ComponentModel m = loader.model(placed.get(i).modelId());
            Matrix4 tf = placed.get(i).transform();
            EngineRenderer.DynamicEntity ent = i < ents.size() ? ents.get(i) : null;
            for (int s = 0; s < m.movableParts.size(); s++) {
                float[] ab = movableWorldAabb(m.movableParts.get(s), tf, ent);
                if (rayHitsAabb(ray, ab, hit)) {
                    float d = ray.origin.dst2(hit);
                    if (d < bestDist) { bestDist = d; best = new Focus(i, s, ab); }
                }
            }
            if (m.collision != null) {
                float[] ab = worldAabb(m.collision, tf);
                if (rayHitsAabb(ray, ab, hit)) {
                    float d = ray.origin.dst2(hit);
                    if (d < bestDist) { bestDist = d; best = new Focus(i, -1, ab); }
                }
            }
        }
        return best;
    }

    private static boolean rayHitsAabb(com.badlogic.gdx.math.collision.Ray ray, float[] ab, Vector3 out) {
        return com.badlogic.gdx.math.Intersector.intersectRayBounds(ray,
                new com.badlogic.gdx.math.collision.BoundingBox(
                        new Vector3(ab[0], ab[1], ab[2]), new Vector3(ab[3], ab[4], ab[5])), out);
    }

    /** World AABB of a movable sub-part (its boxes, at component transform · local · current channel motion), so
     *  the pick hitbox FOLLOWS the moved knob (matches what's rendered). */
    private float[] movableWorldAabb(ComponentModel.MovablePart mv, Matrix4 placement, EngineRenderer.DynamicEntity ent) {
        Matrix4 w = new Matrix4(placement).mul(mv.local());
        if (ent != null) {
            w.mul(mv.binding().toBinding().motion(ent.anim, new Matrix4())); // same motion the renderer applies
        }
        float minx = Float.MAX_VALUE, miny = minx, minz = minx, maxx = -minx, maxy = -minx, maxz = -minx;
        for (PartMesh.Box b : mv.type().boxes()) {
            for (int c = 0; c < 8; c++) {
                tmp2.set(b.cx() + ((c & 1) == 0 ? -b.sx() : b.sx()) / 2f,
                        b.cy() + ((c & 2) == 0 ? -b.sy() : b.sy()) / 2f,
                        b.cz() + ((c & 4) == 0 ? -b.sz() : b.sz()) / 2f).mul(w);
                minx = Math.min(minx, tmp2.x); maxx = Math.max(maxx, tmp2.x);
                miny = Math.min(miny, tmp2.y); maxy = Math.max(maxy, tmp2.y);
                minz = Math.min(minz, tmp2.z); maxz = Math.max(maxz, tmp2.z);
            }
        }
        return new float[]{minx, miny, minz, maxx, maxy, maxz};
    }

    /** True if the focused sub-part is interactive (has any behaviour). */
    public boolean isInteractive(Focus f) { return interactionOf(f) != null; }

    /** DEBUG: "modelId movables=N inter=type" for the focused part. */
    public String debugFocusInfo(Focus f) {
        if (f == null) return "null";
        ComponentModel m = loader.model(placed.get(f.placementIndex()).modelId());
        ComponentModel.Interaction it = interactionOf(f);
        return placed.get(f.placementIndex()).modelId() + " movables=" + m.movableParts.size()
                + " inter=" + (it == null ? "none" : it.type());
    }

    /** TEST: the world AABB {minx,miny,minz,maxx,maxy,maxz} of movable {@code sub} of placement {@code i} (at its
     *  current animated position), or the part's collision box when {@code sub} < 0. */
    public float[] debugSubAabb(int i, int sub) {
        Placed p = placed.get(i);
        ComponentModel m = loader.model(p.modelId());
        if (sub < 0 || sub >= m.movableParts.size()) return worldAabb(m.collision, p.transform());
        return movableWorldAabb(m.movableParts.get(sub), p.transform(), i < ents.size() ? ents.get(i) : null);
    }

    /** TEST: the interactive channel value of placement {@code i} (its first movable's rest {@code min} if it was
     *  never touched); NaN if it has no interactive movable. */
    public float debugChannel(int i) {
        ComponentModel m = loader.model(placed.get(i).modelId());
        for (ComponentModel.MovablePart mv : m.movableParts) {
            ComponentModel.Interaction it = mv.interaction();
            if (it != null) return subState.getOrDefault(i, it.min());
        }
        return Float.NaN;
    }

    /** TEST: is the switch at placement {@code i} currently closed (conducting)? */
    public boolean debugSwitchClosed(int i) { return switchClosed(i, loader.model(placed.get(i).modelId())); }

    /** True if the focused sub-part is DRAG-able (drag_axis / drag_pivot). */
    public boolean isDraggable(Focus f) {
        ComponentModel.Interaction it = interactionOf(f);
        return it != null && (it.type().equals("drag_axis") || it.type().equals("drag_pivot"));
    }

    /** True if the focused sub-part opens a UI on click (click_ui). */
    public boolean isClickUi(Focus f) {
        ComponentModel.Interaction it = interactionOf(f);
        return it != null && it.type().equals("click_ui");
    }

    /** The interaction spec of the sub-part a {@link Focus} points at (null if it's a base or non-interactive). */
    ComponentModel.Interaction interactionOf(Focus f) {
        if (f == null || f.subPart() < 0) {
            return null;
        }
        ComponentModel m = loader.model(placed.get(f.placementIndex()).modelId());
        return f.subPart() < m.movableParts.size() ? m.movableParts.get(f.subPart()).interaction() : null;
    }

    /** The aim's raw projection in CHANNEL units. A one-axis drag has 2 DOF from the mouse but only 1 the knob may
     *  use, so we SACRIFICE the perpendicular freedom and keep only the slide axis:
     *  <ul>
     *    <li><b>drag_axis</b> — the knob is fixed to its world slide LINE {@code rest + t·worldAxis} (t in channel
     *        units). We map the mouse to it by the closest point between the pick RAY and that line — projecting the
     *        aim onto the axis and discarding the perpendicular. This is robust for ANY axis orientation (a
     *        horizontal slider or a vertically-pressed button alike) and has none of the parallax a fixed-height
     *        plane suffers when you look along it.</li>
     *    <li><b>drag_pivot</b> — SAME handle, angular form: the dial turns so the grabbed point stays collinear with
     *        the cursor and the pivot CENTRE. We sample the cursor angle in the dial's ACTUAL rotation plane (through
     *        the pivot, normal = the world rotation axis), so viewing the dial at a shallow angle doesn't parallax
     *        the collinearity. Angle (right-handed about the axis, matching the binding's rotation sense) / degPerUnit.</li>
     *  </ul>
     *  NaN if the ray can't resolve (misses the pivot plane, or runs parallel to the slide axis). */
    private float rawAim(Focus f, com.badlogic.gdx.math.collision.Ray ray) {
        ComponentModel.Interaction it = interactionOf(f);
        if (it == null) return Float.NaN;
        Placed p = placed.get(f.placementIndex());
        ComponentModel.MovablePart mv = loader.model(p.modelId()).movableParts.get(f.subPart());
        Matrix4 tf = p.transform();
        Vector3 rest = mv.local().getTranslation(new Vector3()).mul(tf);

        if (it.type().equals("drag_pivot")) {
            float deg = mv.binding().degPerUnit();
            if (deg == 0f) return 0f;
            float[] ax = mv.binding().axis();
            float[] pv = mv.binding().pivot();
            Vector3 n = new Vector3(ax[0], ax[1], ax[2]).rot(tf).nor();     // world rotation axis
            Vector3 pivot = new Vector3(pv[0], pv[1], pv[2]).mul(tf);
            // Sample on the rotation plane THROUGH the pointer's height (project rest onto the axis from the pivot),
            // so the hit is the real on-face point the cursor is over — parallax-free at any view angle.
            Vector3 planePt = new Vector3(pivot).add(new Vector3(n).scl(new Vector3(rest).sub(pivot).dot(n)));
            Vector3 hit = new Vector3();
            if (!com.badlogic.gdx.math.Intersector.intersectRayPlane(ray,
                    new com.badlogic.gdx.math.Plane(n, planePt), hit)) {
                return Float.NaN;
            }
            // Right-handed angle of (hit - pivot) about n, via an orthonormal in-plane basis (u, w = n×u). Matches
            // the binding, which rotates by +channel·deg about +n, so the pointer tracks the cursor's swing.
            Vector3 ref = Math.abs(n.y) < 0.99f ? new Vector3(0f, 1f, 0f) : new Vector3(1f, 0f, 0f);
            Vector3 u = new Vector3(ref).sub(new Vector3(n).scl(ref.dot(n))).nor();
            Vector3 w = new Vector3(n).crs(u);
            Vector3 v = new Vector3(hit).sub(pivot);
            return (float) Math.toDegrees(Math.atan2(v.dot(w), v.dot(u))) / deg;
        }
        // drag_axis: closest point between the pick ray (origin o, unit dir B) and the slide line (rest, A), where
        // A = worldAxis = one channel unit. Solving the 2-DOF least-squares gives the line param t straight in
        // channel units: t = ((A·B)(B·W0) - (A·W0)) / ((A·A) - (A·B)²), with W0 = rest - o and B normalised.
        float[] ax = mv.binding().axis();
        Vector3 axisW = new Vector3(ax[0], ax[1], ax[2]).rot(tf);
        if (axisW.len2() < 1e-6f) return Float.NaN;
        Vector3 dir = new Vector3(ray.direction).nor();
        Vector3 w0 = new Vector3(rest).sub(ray.origin);
        float aa = axisW.dot(axisW), ab = axisW.dot(dir), bw = dir.dot(w0), aw = axisW.dot(w0);
        float denom = aa - ab * ab;
        if (Math.abs(denom) < 1e-6f) return Float.NaN; // ray runs parallel to the slide axis — no stable answer
        return (ab * bw - aw) / denom;
    }

    /** Starts a drag on the focused sub-part: records the aim + channel at grab time, so subsequent {@link
     *  #aimSubPart} moves the knob by the DELTA (the grabbed point stays under the cursor). */
    public void beginGrab(Focus f, com.badlogic.gdx.math.collision.Ray ray) {
        ComponentModel.Interaction it = interactionOf(f);
        grabValid = it != null && (it.type().equals("drag_axis") || it.type().equals("drag_pivot"));
        if (!grabValid) return;
        grabProj = rawAim(f, ray);
        grabChannel = subState.getOrDefault(f.placementIndex(), it.min());
        if (Float.isNaN(grabProj)) grabValid = false;
    }

    /** AIM-drives the grabbed sub-part: the knob FOLLOWS the crosshair (camera keeps turning freely) by the aim's
     *  DELTA from the grab, so the grabbed point stays under the cursor. Returns true if its state changed. */
    public boolean aimSubPart(Focus f, com.badlogic.gdx.math.collision.Ray ray) {
        ComponentModel.Interaction it = interactionOf(f);
        if (!grabValid || it == null) return false;
        float now = rawAim(f, ray);
        if (Float.isNaN(now)) return false;
        float delta = now - grabProj;
        if (it.type().equals("drag_pivot")) { // unwrap across the atan2 ±180° seam → shortest arc, never a jump
            float deg = Math.abs(loader.model(placed.get(f.placementIndex()).modelId())
                    .movableParts.get(f.subPart()).binding().degPerUnit());
            if (deg > 0f) { float rev = 360f / deg; delta -= Math.round(delta / rev) * rev; }
        }
        float c = Math.max(it.min(), Math.min(it.max(), grabChannel + delta));
        float prev = subState.getOrDefault(f.placementIndex(), it.min());
        if (Math.abs(c - prev) < 1e-4f) return false;
        subState.put(f.placementIndex(), c);
        ents.get(f.placementIndex()).anim.set(
                loader.model(placed.get(f.placementIndex()).modelId()).movableParts.get(f.subPart()).binding().channel(), c);
        return true;
    }

    /** The resistance (Ω) of placement {@code i}: if it has a movable that {@code drives="resistance"} (a variable
     *  resistor), its channel maps 0..1 → 10Ω..1000Ω; otherwise the fixed 100Ω. */
    private double resistanceOhms(int i, ComponentModel m) {
        for (ComponentModel.MovablePart mv : m.movableParts) {
            ComponentModel.Interaction it = mv.interaction();
            if (it != null && "resistance".equals(it.drives())) {
                float frac = it.max() == it.min() ? 0f
                        : (subState.getOrDefault(i, it.min()) - it.min()) / (it.max() - it.min());
                return 10.0 + Math.max(0f, Math.min(1f, frac)) * 990.0;
            }
        }
        return 100.0;
    }

    /** A switch part's closed/open state (a movable that {@code drives="switch"}): closed when its channel is past
     *  the mid-travel. Defaults to OPEN (rest). Non-switch conductors (plain wire/tee) always conduct. */
    private boolean switchClosed(int i, ComponentModel m) {
        for (ComponentModel.MovablePart mv : m.movableParts) {
            ComponentModel.Interaction it = mv.interaction();
            if (it != null && "switch".equals(it.drives())) {
                return subState.getOrDefault(i, it.min()) > (it.min() + it.max()) / 2f;
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

    /** Draws one deck CARD: a registered model at an arbitrary HUD pose + camera, opaque. Reuses the ghost mesh
     *  path (all catalog models are registered as ghost models at construction). No-op for the empty Cursor card.
     *  Call inside a HUD pass (its own camera, depth buffer cleared) AFTER {@link #render}. */
    public void drawCard(Camera cam, String modelId, Matrix4 pose) {
        if (modelId == null || modelId.isEmpty() || !built) return;
        engine.drawGhost(cam, modelId, pose, 1f, 1f, 1f, 1f);
    }

    /** The visual AABB of a model (min xyz, max xyz — full art extent, not the base-plate collision box), for
     *  sizing/centering a deck card. Returns a 6-float {minX,minY,minZ,maxX,maxY,maxZ}; a unit box if unknown. */
    public float[] modelExtent(String modelId) {
        float[] r = {-1f, 0f, -1f, 1f, 1f, 1f};
        if (modelId == null || modelId.isEmpty()) return r;
        ComponentModel m = loader.model(modelId);
        boolean any = false;
        float minx = Float.MAX_VALUE, miny = Float.MAX_VALUE, minz = Float.MAX_VALUE;
        float maxx = -Float.MAX_VALUE, maxy = -Float.MAX_VALUE, maxz = -Float.MAX_VALUE;
        for (PartMesh.Box b : m.staticBoxes) {
            any = true;
            minx = Math.min(minx, b.cx() - b.sx() / 2f); maxx = Math.max(maxx, b.cx() + b.sx() / 2f);
            miny = Math.min(miny, b.cy() - b.sy() / 2f); maxy = Math.max(maxy, b.cy() + b.sy() / 2f);
            minz = Math.min(minz, b.cz() - b.sz() / 2f); maxz = Math.max(maxz, b.cz() + b.sz() / 2f);
        }
        return any ? new float[]{minx, miny, minz, maxx, maxy, maxz} : r;
    }

    public List<Placed> placements() {
        return placed;
    }

    @Override
    public void dispose() {
        engine.dispose();
    }
}
