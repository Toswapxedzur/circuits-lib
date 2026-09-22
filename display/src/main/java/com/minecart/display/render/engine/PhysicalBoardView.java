package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.minecart.display.render.engine.behaviour.InteractiveBehaviour;
import com.minecart.display.snap.SnapModelBridge.Electrical;
import com.minecart.logic.PhysicalCircuitBuilder;
import com.minecart.logic.PhysicalCircuitBuilder.Kind;

import java.util.ArrayList;
import java.util.List;

/**
 * The <b>physical free-placement</b> board (the Minecraft-grid replacement): parts live at continuous world
 * transforms and connect where their {@link ComponentModel.Connector connectors} physically coincide — no lattice.
 * A part is positioned freely; when one of its connectors comes near a compatible connector already on the board
 * it MAGNETICALLY snaps to mate exactly. Placement is rejected when the part's collision box overlaps another.
 *
 * <p>This is the display-side seam (like {@link EngineBoardView}): it holds the placements + the engine renderer,
 * and exposes snap / collision / connector queries for the editor. Electrical connectivity — unioning coincident
 * connectors into circuit nodes — is domain logic and lives in the core {@link com.minecart.logic.PhysicalCircuitBuilder};
 * this board only computes each placement's terminal geometry ({@link #terminalXZ}) and hands it over.
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
    // Interactive-controls driver (drag/grab bookkeeping + per-part control state). Shares placed/ents by reference.
    private final BoardInteraction interaction = new BoardInteraction(placed, ents, loader);
    // Reactive-animation driver (electrical state → per-part spin/glow/swell each frame). Reads placed/ents/deviceEdge.
    private final BoardReactive reactive = new BoardReactive(placed, ents, deviceEdge);
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

    private final Vector3 tmp = new Vector3();

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
            float[] b = BoardGeometry.collisionWorldAabb(pm.collision, placed.get(i).transform());
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

    /** TEST SEAM: append a placement WITHOUT the GL {@link #rebuild()} — lets a headless test exercise
     *  {@link #buildCircuit} (pure logic) without a GL context. Not for runtime use. */
    void addPlacementNoRender(String modelId, Matrix4 transform) {
        placed.add(new Placed(modelId, new Matrix4(transform)));
    }

    /** The circuit edge a device placement contributed (resistor/battery/…/transistor collector), or null. */
    public com.minecart.logic.CircuitEdge debugDeviceEdge(int i) {
        return deviceEdge.get(i);
    }

    /** DESIGN WORLD: lays {@code ids} out in a grid — {@code perRow} parts per row, 48×36 units per cell — placing
     *  each through the normal {@link #snap}/{@link #canPlace} path, so every part is grid-aligned by construction
     *  and a model whose registered ports don't fit the grid shows up as BLOCKED. Returns a one-line report. */
    public String designLayout(List<String> ids, int perRow) {
        StringBuilder blocked = new StringBuilder();
        int n = 0;
        for (int k = 0; k < ids.size(); k++) {
            String id = ids.get(k);
            float x = 24f + (k % perRow) * 48f, z = 24f + (k / perRow) * 36f;
            Matrix4 m = snap(id, new Matrix4().setToTranslation(x, 0f, z));
            if (canPlace(id, m)) { place(id, m); n++; }
            else blocked.append(id).append(' ');
        }
        return "placed " + n + "/" + ids.size() + (blocked.length() == 0 ? "" : "  BLOCKED: " + blocked);
    }

    /** Removes every placement (test/reset helper). */
    public void clearAll() {
        placed.clear();
        deviceEdge.clear();
        interaction.resetState();
        rebuild(); // rebuild recreates entities, so per-part spin/swell channels reset with them

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
            interaction.resetState(); // indices shift on remove — reset interactive states (rare, acceptable)
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
            // No whole-body tint: it painted the LED's BASE red too, so a placed LED looked nothing like its card /
            // ghost (owner: "it's red", 2026-09-10). An LED's colour belongs in its model art (datagen, dome only);
            // when current flows it EMITS its colour via updateElectricalGlow.
            engine.addEntity(e);
            ents.add(e);
        }
        engine.build();
        built = hasBase || !placed.isEmpty();
    }

    private long lastFrameNanos; // for per-frame dt

    /** TEST: motor {@code i}'s current spin channel (0..1 = one turn), or NaN if it's not a motor. */
    public float debugSpin(int i) { return reactive.debugSpin(i); }

    /** TEST: capacitor {@code i}'s swell channel (0 = uncharged/identity, grows with charge), or NaN if not a cap. */
    public float debugSwell(int i) { return reactive.debugSwell(i); }

    private com.minecart.logic.CircuitEdge lastBattery; // captured to read solved current (a live-circuit proof)

    /**
     * Rebuilds the world's ELECTRICAL circuit from the physical placements. This board computes each placement's
     * terminal world (x,z) + electrical kind + params and hands a gdx-free plan to the core
     * {@link com.minecart.logic.PhysicalCircuitBuilder}, which unions coincident connectors into shared
     * {@link com.minecart.logic.CircuitNode}s, attaches device elements and wires transistors. Call after every
     * place/remove; reads back {@code deviceEdge} (glow/spin) + {@code lastBattery} (HUD).
     */
    public void buildCircuit(com.minecart.logic.ServerWorld world) {
        // Build a plain, gdx-free plan (each placement's terminal world x,z + electrical kind + params) and hand it
        // to the core PhysicalCircuitBuilder, which owns the connector-field union-find, device attach and BJT
        // wiring. This board keeps only presentation: it reads back deviceEdge (glow/spin) + lastBattery (HUD).
        List<PhysicalCircuitBuilder.Part> plan = new ArrayList<>(placed.size());
        for (int i = 0; i < placed.size(); i++) {
            Placed p = placed.get(i);
            // Static role + params are DATA on the Electrical enum (behaviour-on-type). Only the two control-driven
            // roles resolve dynamically here from the interactive state: a switch gates its conductor on the toggle,
            // and a resistor's ohms come from the (optional) var-res control (fixed 100Ω otherwise).
            Electrical el = electrical(p.modelId());
            Kind pk = el.kind;
            double[] params = el.params;
            if (el == Electrical.SWITCH) {
                pk = interaction.switchClosed(i) ? Kind.CONDUCTOR : Kind.NONE;
            } else if (el == Electrical.RESISTOR) {
                params = new double[]{interaction.resistanceOhms(i)};
            }
            plan.add(new PhysicalCircuitBuilder.Part(i, terminalXZ(p), pk, params));
        }
        PhysicalCircuitBuilder.Result res = PhysicalCircuitBuilder.build(world, plan);
        deviceEdge.clear();
        deviceEdge.putAll(res.edges());
        lastBattery = res.lastBattery();
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
                float[] xz = terminalXZ(p);
                if (xz.length >= 4) return new String[]{
                        PhysicalCircuitBuilder.key(xz[0], xz[1]), PhysicalCircuitBuilder.key(xz[2], xz[3])};
            }
        }
        return new String[0];
    }

    /** The most recent battery's solved current magnitude (amps) — a live-circuit readout; 0 if none/unsolved. */
    public double batteryCurrent() {
        return lastBattery == null ? 0.0 : Math.abs(lastBattery.getCurrent().getValue());
    }

    static Electrical electrical(String modelId) {
        return com.minecart.display.snap.SnapModelBridge.electricalOf(modelId);
    }

    /** A placement's terminal world (x,z) positions as flat pairs, ordered by terminal index (terminal k at
     *  {@code [2k],[2k+1]}). This is the whole geometric contract the core {@link PhysicalCircuitBuilder} needs. */
    private float[] terminalXZ(Placed p) {
        ComponentModel m = loader.model(p.modelId());
        int n = m.connectors.size();
        float[] xz = new float[n * 2];
        for (ComponentModel.Connector c : m.connectors) {
            int k = c.terminal();
            if (k < 0 || 2 * k + 1 >= xz.length) continue;
            Vector3 w = new Vector3(c.local()).mul(p.transform());
            xz[2 * k] = w.x;
            xz[2 * k + 1] = w.z;
        }
        return xz;
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
     * part at all, so the caller falls back to the board plane. This is what lets you deliberately build UPWARD.
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
            float[] ab = BoardGeometry.collisionWorldAabb(pm.collision, p.transform());
            com.badlogic.gdx.math.collision.BoundingBox bb = new com.badlogic.gdx.math.collision.BoundingBox(
                    new Vector3(ab[0], ab[1], ab[2]), new Vector3(ab[3], ab[4], ab[5]));
            // ANY face of the part's box aliases a stud (owner: "a portion of a face is an alias of a port"). The old
            // top-face-only rule made a low-angle aim at a part's side fall back to the board point INSIDE the part →
            // a BLOCKED ghost drawn coincident with it (the "it's red" bug, 2026-09-09). A ray that misses the box
            // entirely still falls back to the board, so aiming past a part at the ground behind it is unchanged.
            if (com.badlogic.gdx.math.Intersector.intersectRayBounds(ray, bb, hit)) {
                float d = ray.origin.dst2(hit);
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
        float[] a = BoardGeometry.collisionWorldAabb(m.collision, transform);
        for (Placed p : placed) {
            ComponentModel pm = loader.model(p.modelId());
            if (pm.collision == null) {
                continue;
            }
            if (BoardGeometry.overlap(a, BoardGeometry.collisionWorldAabb(pm.collision, p.transform()))) {
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
                if (BoardGeometry.rayHitsAabb(ray, ab, hit)) {
                    float d = ray.origin.dst2(hit);
                    if (d < bestDist) { bestDist = d; best = new Focus(i, s, ab); }
                }
            }
            // The base is picked by the MODEL'S OWN BOXES (its real shape, like Minecraft's voxel shape), so aiming at
            // empty air beside a dome doesn't focus the part. Focus.aabb stays the whole part's box (aim/debug).
            for (PartMesh.Box b : m.staticBoxes) {
                if (BoardGeometry.rayHitsAabb(ray, BoardGeometry.boxWorldAabb(b, tf), hit)) {
                    float d = ray.origin.dst2(hit);
                    if (d < bestDist) {
                        bestDist = d;
                        best = new Focus(i, -1, BoardGeometry.collisionWorldAabb(m.visual != null ? m.visual : m.collision, tf));
                    }
                }
            }
        }
        return best;
    }

    /** World AABB of a movable sub-part (its boxes, at component transform · local · current channel motion), so
     *  the pick hitbox FOLLOWS the moved knob (matches what's rendered). */
    /** World matrix of a movable sub-part: placement · local · its current channel motion (what the renderer uses). */
    private Matrix4 movableWorldMatrix(ComponentModel.MovablePart mv, Matrix4 placement, EngineRenderer.DynamicEntity ent) {
        Matrix4 w = new Matrix4(placement).mul(mv.local());
        if (ent != null) {
            w.mul(mv.binding().toBinding().motion(ent.anim, new Matrix4())); // same motion the renderer applies
        }
        return w;
    }

    private float[] movableWorldAabb(ComponentModel.MovablePart mv, Matrix4 placement, EngineRenderer.DynamicEntity ent) {
        Matrix4 w = movableWorldMatrix(mv, placement, ent);
        float minx = Float.MAX_VALUE, miny = minx, minz = minx, maxx = -minx, maxy = -minx, maxz = -minx;
        for (PartMesh.Box b : mv.type().boxes()) {
            float[] a = BoardGeometry.boxWorldAabb(b, w);
            minx = Math.min(minx, a[0]); miny = Math.min(miny, a[1]); minz = Math.min(minz, a[2]);
            maxx = Math.max(maxx, a[3]); maxy = Math.max(maxy, a[4]); maxz = Math.max(maxz, a[5]);
        }
        return new float[]{minx, miny, minz, maxx, maxy, maxz};
    }

    // ── Focus OUTLINE = the model's own shape (owner 2026-09-10: "the bounding box is derived from the component model
    // itself, not any bounding box") — like Minecraft outlining a block's voxel shape. We draw only the CREASE edges
    // of the union of the model's boxes: an edge is kept iff both faces meeting at it are exposed there (a sample
    // point just outside each face lies in no box). Flush seams between stacked/adjacent boxes vanish; plate, stud,
    // dome and knob silhouettes remain. Computed once per model / part-type in object space, transformed per draw.
    private final java.util.Map<Object, float[]> edgeCache = new java.util.HashMap<>();

    /** World-space outline segments of what {@code f} focuses — the part's own shape, or its movable sub-part's —
     *  with HIDDEN LINES REMOVED in software: every edge is sampled and each sample is ray-cast from {@code eye}
     *  against the part's own boxes (and any other placed part in the way); only the visible runs are returned.
     *  The lines lie EXACTLY on the model's edges (owner: no bloated outline) — no depth bias, no expansion. */
    public float[] focusEdges(Focus f, Vector3 eye) {
        if (f == null) return new float[0];
        Placed p = placed.get(f.placementIndex());
        ComponentModel m = loader.model(p.modelId());
        float[] local;
        Matrix4 w;
        List<float[]> occ = new ArrayList<>(); // world AABBs that can hide this outline
        EngineRenderer.DynamicEntity ent = f.placementIndex() < ents.size() ? ents.get(f.placementIndex()) : null;
        if (f.subPart() >= 0 && f.subPart() < m.movableParts.size()) {
            ComponentModel.MovablePart mv = m.movableParts.get(f.subPart());
            local = edgeCache.computeIfAbsent(mv.type(), k -> BoardGeometry.shapeEdges(mv.type().boxes()));
            w = movableWorldMatrix(mv, p.transform(), ent);
            for (PartMesh.Box b : mv.type().boxes()) occ.add(BoardGeometry.boxWorldAabb(b, w));
        } else {
            local = edgeCache.computeIfAbsent(p.modelId(), k -> BoardGeometry.shapeEdges(m.staticBoxes));
            w = p.transform();
        }
        for (PartMesh.Box b : m.staticBoxes) occ.add(BoardGeometry.boxWorldAabb(b, p.transform()));
        for (int s = 0; s < m.movableParts.size(); s++) { // the part's own knobs can hide its base edges too
            ComponentModel.MovablePart mv = m.movableParts.get(s);
            Matrix4 mw = movableWorldMatrix(mv, p.transform(), ent);
            for (PartMesh.Box b : mv.type().boxes()) occ.add(BoardGeometry.boxWorldAabb(b, mw));
        }
        for (int i = 0; i < placed.size(); i++) { // other parts: only those whose whole box the eye→part ray can cross
            if (i == f.placementIndex()) continue;
            ComponentModel om = loader.model(placed.get(i).modelId());
            ComponentModel.Collision ob = om.visual != null ? om.visual : om.collision;
            if (ob == null) continue;
            float[] whole = BoardGeometry.collisionWorldAabb(ob, placed.get(i).transform());
            Vector3 c = new Vector3((f.aabb()[0] + f.aabb()[3]) / 2f, (f.aabb()[1] + f.aabb()[4]) / 2f, (f.aabb()[2] + f.aabb()[5]) / 2f);
            if (BoardGeometry.rayBoxEntry(eye, c.sub(eye), whole) < 1.2f) { // near the line of sight → its boxes are occluders
                for (PartMesh.Box b : om.staticBoxes) occ.add(BoardGeometry.boxWorldAabb(b, placed.get(i).transform()));
            }
        }
        java.util.List<Float> out = new java.util.ArrayList<>();
        Vector3 a = new Vector3(), b = new Vector3(), d = new Vector3(), pt = new Vector3(), dir = new Vector3();
        for (int i = 0; i + 5 < local.length; i += 6) {
            a.set(local[i], local[i + 1], local[i + 2]).mul(w);
            b.set(local[i + 3], local[i + 4], local[i + 5]).mul(w);
            d.set(b).sub(a);
            int n = Math.max(4, Math.min(32, Math.round(d.len() / 1.5f)));
            int runStart = -1;
            for (int k = 0; k <= n; k++) {
                boolean vis = false;
                if (k < n) {
                    pt.set(a).mulAdd(d, (k + 0.5f) / n);
                    dir.set(pt).sub(eye);
                    vis = true;
                    for (float[] box : occ) {
                        float t = BoardGeometry.rayBoxEntry(eye, dir, box);
                        if (t > 1e-4f && t < 1f - 1e-3f) { vis = false; break; } // something strictly in front
                    }
                }
                if (vis && runStart < 0) runStart = k;
                if (!vis && runStart >= 0) { // emit the visible run [runStart, k)
                    out.add(a.x + d.x * runStart / n); out.add(a.y + d.y * runStart / n); out.add(a.z + d.z * runStart / n);
                    out.add(a.x + d.x * k / n);        out.add(a.y + d.y * k / n);        out.add(a.z + d.z * k / n);
                    runStart = -1;
                }
            }
        }
        float[] r = new float[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** TEST: outline statistics for placement {@code i} seen from {@code (ex,ey,ez)} — crease edges, visible segments,
     *  and for the first few samples of the first edge every box that claims to occlude them (t in (0,1)). */
    public String debugOutline(int i, float ex, float ey, float ez) {
        Placed p = placed.get(i);
        ComponentModel m = loader.model(p.modelId());
        float[] local = edgeCache.computeIfAbsent(p.modelId(), k -> BoardGeometry.shapeEdges(m.staticBoxes));
        Vector3 eye = new Vector3(ex, ey, ez);
        Focus f = new Focus(i, -1, BoardGeometry.collisionWorldAabb(m.visual != null ? m.visual : m.collision, p.transform()));
        float[] segs = focusEdges(f, eye);
        StringBuilder sb = new StringBuilder("edges=" + local.length / 6 + " visibleSegs=" + segs.length / 6 + " boxes=" + m.staticBoxes.size());
        for (int k = 0; k < Math.min(segs.length, 18); k += 6) {
            sb.append(" seg").append(k / 6).append("=(").append(segs[k]).append(',').append(segs[k + 1]).append(',').append(segs[k + 2])
              .append(")->(").append(segs[k + 3]).append(',').append(segs[k + 4]).append(',').append(segs[k + 5]).append(')');
        }
        if (local.length >= 6) {
            Vector3 a = new Vector3(local[0], local[1], local[2]).mul(p.transform());
            Vector3 b = new Vector3(local[3], local[4], local[5]).mul(p.transform());
            Vector3 pt = new Vector3(a).lerp(b, 0.5f), dir = new Vector3(pt).sub(eye);
            sb.append(" edge0=").append(a).append("→").append(b).append(" mid=").append(pt);
            int bi = 0;
            for (PartMesh.Box bx : m.staticBoxes) {
                float[] wb = BoardGeometry.boxWorldAabb(bx, p.transform());
                float t = BoardGeometry.rayBoxEntry(eye, dir, wb);
                if (t > 1e-4f && t < 1f - 1e-3f) sb.append(" HIT box").append(bi).append(" t=").append(t)
                        .append(" [").append(wb[0]).append(',').append(wb[1]).append(',').append(wb[2]).append("..")
                        .append(wb[3]).append(',').append(wb[4]).append(',').append(wb[5]).append(']');
                bi++;
            }
        }
        return sb.toString();
    }

    /** True if the focused sub-part is interactive (has a drag control). */
    public boolean isInteractive(Focus f) { return interaction.isInteractive(f); }

    /** DEBUG: "modelId movables=N inter=channel" for the focused part. */
    public String debugFocusInfo(Focus f) {
        if (f == null) return "null";
        ComponentModel m = loader.model(placed.get(f.placementIndex()).modelId());
        InteractiveBehaviour b = interaction.interactiveFor(f);
        return placed.get(f.placementIndex()).modelId() + " movables=" + m.movableParts.size()
                + " inter=" + (b == null ? "none" : b.channel() + (b.pivotDrag() ? "/pivot" : "/axis"));
    }

    /** TEST: WHY would {@link #canPlace} reject (or accept) this transform — every terminal's socket + support
     *  verdict, and the first placed part whose 3D box overlaps. Turns a bare BLOCKED into a diagnosis. */
    public String explainPlace(String modelId, Matrix4 transform) {
        ComponentModel m = loader.model(modelId);
        StringBuilder sb = new StringBuilder(modelId).append(" at ").append(transform.getTranslation(new Vector3())).append(": ");
        int k = 0;
        for (ComponentModel.Connector c : m.connectors) {
            Vector3 w = new Vector3(c.local()).mul(transform);
            sb.append("t").append(k++).append('=').append(w).append(onSocket(w) ? " socket✓" : " OFF-SOCKET✗")
              .append(studSupported(w, -1) ? " supported✓" : " UNSUPPORTED✗").append("; ");
        }
        if (m.collision != null) {
            float[] a = BoardGeometry.collisionWorldAabb(m.collision, transform);
            sb.append("box y").append(a[1]).append("..").append(a[4]).append("; ");
            for (int i = 0; i < placed.size(); i++) {
                ComponentModel pm = loader.model(placed.get(i).modelId());
                if (pm.collision == null) continue;
                float[] b = BoardGeometry.collisionWorldAabb(pm.collision, placed.get(i).transform());
                if (BoardGeometry.overlap(a, b)) sb.append("OVERLAPS placement ").append(i).append(" (").append(placed.get(i).modelId())
                        .append(" box y").append(b[1]).append("..").append(b[4]).append("); ");
            }
        }
        return sb.append(canPlace(modelId, transform) ? "=> VALID" : "=> BLOCKED").toString();
    }

    /** TEST: the world AABB {minx,miny,minz,maxx,maxy,maxz} of movable {@code sub} of placement {@code i} (at its
     *  current animated position), or the part's collision box when {@code sub} < 0. */
    public float[] debugSubAabb(int i, int sub) {
        Placed p = placed.get(i);
        ComponentModel m = loader.model(p.modelId());
        if (sub < 0 || sub >= m.movableParts.size()) return BoardGeometry.collisionWorldAabb(m.visual != null ? m.visual : m.collision, p.transform());
        return movableWorldAabb(m.movableParts.get(sub), p.transform(), i < ents.size() ? ents.get(i) : null);
    }

    /** TEST: the interactive channel value of placement {@code i} (its first movable's rest {@code min} if it was
     *  never touched); NaN if it has no interactive movable. */
    public float debugChannel(int i) { return interaction.debugChannel(i); }

    /** TEST: is the switch at placement {@code i} currently closed (conducting)? */
    public boolean debugSwitchClosed(int i) { return interaction.switchClosed(i); }

    /** True if the focused sub-part is DRAG-able. Every interactive control is a drag (linear or rotary). */
    public boolean isDraggable(Focus f) {
        return interaction.isInteractive(f);
    }

    /** True if the focused sub-part opens a UI on click. No control is click-UI today (all are drags). */
    public boolean isClickUi(Focus f) {
        return false;
    }

    /** Starts a drag on the focused sub-part (records the aim + channel at grab time; a momentary button presses
     *  fully closed). Delegates to {@link BoardInteraction}. */
    public void beginGrab(Focus f, com.badlogic.gdx.math.collision.Ray ray) {
        interaction.beginGrab(f, ray);
    }

    /** True if the focused sub-part is a momentary control (springs back on release). */
    public boolean isMomentary(Focus f) {
        return interaction.isMomentary(f);
    }

    /** Eases every momentary control (except the grabbed one) back toward rest; returns true if any crossed its
     *  conduct threshold so the caller re-solves the circuit. Delegates to {@link BoardInteraction}. */
    public boolean tickMomentary(float dt, int grabbedIdx) {
        return interaction.tickMomentary(dt, grabbedIdx);
    }

    /** AIM-drives the grabbed sub-part so the grabbed point follows the crosshair; true if its state changed. */
    public boolean aimSubPart(Focus f, com.badlogic.gdx.math.collision.Ray ray) {
        return interaction.aimSubPart(f, ray);
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
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0f : Math.min(0.1f, (now - lastFrameNanos) / 1e9f);
        lastFrameNanos = now;
        reactive.update(dt); // live current/charge → per-part motion + emission (behaviours) before the lighting pass
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
        ComponentModel.Collision v = m.visual; // REGISTERED by datagen (never derived here)
        if (v == null) return r;
        return new float[]{v.cx() - v.hx(), v.cy() - v.hy(), v.cz() - v.hz(), v.cx() + v.hx(), v.cy() + v.hy(), v.cz() + v.hz()};
    }

    public List<Placed> placements() {
        return placed;
    }

    @Override
    public void dispose() {
        engine.dispose();
    }
}
