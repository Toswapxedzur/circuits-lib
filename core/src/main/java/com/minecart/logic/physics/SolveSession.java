package com.minecart.logic.physics;

import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerWorld;
import com.minecart.physics.AnchorPoint;
import com.minecart.physics.Body;
import com.minecart.physics.Solver;
import com.minecart.physics.SolverConfig;
import com.minecart.physics.SolverResult;
import com.minecart.physics.Vec2;
import com.minecart.physics.constraint.Constraint;
import com.minecart.physics.constraint.DistanceConstraint;
import com.minecart.physics.constraint.PinJointConstraint;
import com.minecart.physics.constraint.SpringConstraint;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.LockMode;
import com.minecart.variant.info.LockState;
import com.minecart.variant.info.PositionInfo;
import com.minecart.variant.info.RigidityInfo;
import com.minecart.variant.info.RotationInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One self-contained physics solve over the sub-graph kinematically reachable from a seed element.
 * A session is built ({@link #build}), the seed's gesture is applied
 * ({@link #lockSeedTranslated}/{@link #lockSeedRotated}), the solver runs ({@link #solve}), and
 * the resulting body poses are written back to the domain entities ({@link #writeback}).
 *
 * <h2>Lifetime</h2>
 * A session captures a snapshot of the topology at construction time. If the world's topology
 * changes after a session is built (a wire is added / removed, a port is swapped), the cached
 * body / constraint references can go stale. The current cascade entry points
 * ({@link com.minecart.logic.cascade.CombineCascadeEngine}) build a fresh session per gesture so
 * staleness doesn't apply there; the drag-lease cache (when wired) is responsible for invalidating
 * cached sessions on topology changes.
 *
 * <h2>Determinism</h2>
 * The BFS visits elements in insertion-order across the data structures' {@code LinkedHash*}
 * iteration: components in their port-registration order, ports in their slot order, edges in
 * the order they appear in each node's connection set. Constraints are emitted in the order they
 * were discovered. Two solves on the same input topology produce the same constraint list and
 * therefore the same output pose, modulo floating-point determinism of the solver itself.
 */
public final class SolveSession {

    /**
     * Default solver config used by the adapter. The editor-tuned {@link SolverConfig#defaults}
     * preset is 20 iterations / 1e-4 residual; we bump to 50 iterations because circuit chains
     * are often longer than the physics-module's unit-test workloads and the extra headroom
     * doesn't measurably affect single-drag latency at editor scale.
     */
    static final SolverConfig DEFAULT_CONFIG = SolverConfig.defaults().withMaxIterations(50);

    private final ServerWorld world;
    /**
     * Body for each domain element in the active set. Key is the element's UUID; the element
     * itself may be a {@link CircuitComponent} or a free {@link CircuitNode}. Port nodes inside
     * a component are NOT keys here — their pose is derived from the parent body's pose at
     * writeback time. Iteration order matches BFS discovery order so the solver sees a
     * deterministic constraint sequence.
     */
    private final Map<UUID, Body> bodyById = new LinkedHashMap<>();
    /** Reverse lookup: element id → the domain element. Used at writeback time. */
    private final Map<UUID, CircuitElement> elementById = new LinkedHashMap<>();
    /**
     * For each component body, the local-frame offsets of every internal node (ports + non-port
     * internals), captured at session-build time. Writeback applies each component body's final
     * pose to these snapshots so the internal nodes' world positions move rigidly with the
     * component without depending on the {@link ComponentAnchorRegistry} (which the test harness
     * doesn't always populate, and which doesn't match nodes manually repositioned mid-session).
     *
     * <p>Outer key is the component's UUID; inner map is internal-node-id → local offset.
     * Iteration order matches BFS discovery for deterministic notify ordering.
     */
    private final Map<UUID, Map<UUID, Vec2>> internalLocalOffsets = new LinkedHashMap<>();
    /** Reverse lookup of internal node id → CircuitNode for writeback's notify path. */
    private final Map<UUID, CircuitNode> internalNodeById = new LinkedHashMap<>();
    private final List<Constraint> constraints = new ArrayList<>();

    /**
     * BFS scratch state lifted to session scope so multiple {@link #collect} invocations against
     * the same session deduplicate naturally. The first BFS visits some sub-graph; a second BFS
     * from a different seed that overlaps it (or that lands in the same connected component via
     * a previously-undiscovered path) sees the prior visit marks and skips re-emitting bodies,
     * edges, or shared-port pin joints.
     */
    private final Set<CircuitElement> visited = new LinkedHashSet<>();
    private final Set<CircuitEdge> edgesToConstrain = new LinkedHashSet<>();
    private final Map<UUID, CircuitNode> sharedPortsByNodeId = new LinkedHashMap<>();
    /**
     * Whether the constraint list has been emitted yet. {@link #collect} appends to the BFS
     * scratch sets; the actual constraint construction is deferred until the first call to a
     * solve / read accessor that needs them, via {@link #finaliseConstraints}. This lets the
     * multi-seed entry point call {@link #collect} multiple times before emitting.
     */
    private boolean constraintsFinalised = false;

    private SolveSession(ServerWorld world) {
        this.world = world;
    }

    /**
     * Discovers every element kinematically reachable from {@code seed}, lifts each one to a
     * {@link Body}, and emits constraints for rigid edges and multi-owner port nodes.
     */
    public static SolveSession build(ServerWorld world, CircuitElement seed) {
        SolveSession s = new SolveSession(world);
        s.collect(seed);
        s.finaliseConstraints();
        return s;
    }

    /**
     * Multi-seed equivalent of {@link #build}: runs the BFS from <em>each</em> seed against the
     * same shared scratch state, so the resulting body / constraint set is the union of all
     * reachable sub-graphs. Overlapping seeds (the common case when two gestures hit the same
     * connected sub-graph) just visit each element once. Disjoint seeds produce a session whose
     * body map covers multiple components; the constraint list links each one's internal rigid
     * edges and shared ports, with no cross-component constraints because none exist.
     *
     * <p>Constraints are finalised before return — callers can immediately add gesture-specific
     * springs / locks via {@link #addCentreSpring}, {@link #lockBodyInPlace}, etc., and then
     * {@link #solve}.
     */
    public static SolveSession buildMulti(ServerWorld world, Collection<? extends CircuitElement> seeds) {
        SolveSession s = new SolveSession(world);
        if (seeds != null) {
            for (CircuitElement seed : seeds) {
                s.collect(seed);
            }
        }
        s.finaliseConstraints();
        return s;
    }

    /** Read-only view of the active body map. Exposed for tests and adapter-level assertions. */
    public Map<UUID, Body> bodies() {
        return java.util.Collections.unmodifiableMap(bodyById);
    }

    /** Read-only view of the discovered constraint list. */
    public List<Constraint> constraints() {
        return java.util.Collections.unmodifiableList(constraints);
    }

    /**
     * Whether {@code element} is part of this session's active set. Used by callers (e.g. the
     * cascade engine's preflight) to confirm the seed actually made it into the body graph
     * before applying a gesture.
     */
    public boolean contains(CircuitElement element) {
        return element != null && bodyById.containsKey(element.getId());
    }

    /**
     * Pins the seed body at its current position + {@code (dx, dy)} (so other bodies linked via
     * constraints follow). Returns {@code false} if the seed has no body in this session — which
     * shouldn't happen under normal use, but guards against caller bugs.
     */
    public boolean lockSeedTranslated(CircuitElement seed, double dx, double dy) {
        Body b = bodyById.get(seed.getId());
        if (b == null) {
            return false;
        }
        // Pin the seed at the post-translation pose, regardless of its prior inverse inertias.
        // This is the "drag = locked cursor" model: the seed becomes a kinematic boundary, and
        // the solver propagates corrections through the constraint graph to the rest of the
        // active set. The seed's prior inverse inertias don't matter because the gesture
        // pre-empts them — the caller's lock preflight already confirmed the gesture was legal.
        Vec2 newPos = b.position().add(new Vec2(dx, dy));
        b.setPosition(newPos);
        b.setRotationPivot(newPos);
        b.setInvMassT(0.0);
        b.setInvMassR(0.0);
        return true;
    }

    /**
     * Pins the seed body at the post-rotation pose: position rotated around {@code (pivotX,
     * pivotY)} by {@code deltaRadians}, angle advanced by the same delta. Other bodies follow
     * via the constraint graph. Returns {@code false} if the seed isn't in the active set.
     *
     * <p>"Locks always win" falls out naturally: bodies elsewhere in the chain that are
     * rotation-locked ({@code invMassR == 0}) refuse to spin under any correction the solver
     * applies, so the gesture only lands as far as the constraint graph permits.
     */
    public boolean lockSeedRotated(CircuitElement seed, double pivotX, double pivotY,
                                   double deltaRadians) {
        Body b = bodyById.get(seed.getId());
        if (b == null) {
            return false;
        }
        Vec2 pivot = new Vec2(pivotX, pivotY);
        double cos = Math.cos(deltaRadians);
        double sin = Math.sin(deltaRadians);
        Vec2 relative = b.position().sub(pivot);
        Vec2 newPos = pivot.add(relative.rotate(cos, sin));
        b.setPosition(newPos);
        b.setAngle(b.angle() + deltaRadians);
        b.setRotationPivot(newPos);
        b.setInvMassT(0.0);
        b.setInvMassR(0.0);
        return true;
    }

    /**
     * Attaches a {@link SpringConstraint} from {@code element}'s body centre to the world-space
     * target {@code (targetX, targetY)}. This is the "drag = soft pull toward cursor" gesture
     * model: the spring lives in the constraint list and is projected each solver iteration,
     * with its XPBD compliance parameter giving callers the stiffness knob.
     *
     * <h2>Constraint ordering</h2>
     * Springs are inserted at the <em>front</em> of the constraint list, so each solver iteration
     * runs the springs first (applying the cursor-pull force) and then the rigid edges / pin
     * joints (projecting the body back onto the rigid constraint manifold). This ordering matches
     * the standard "apply forces, then satisfy constraints" structure of a physics integration
     * step and gives the steady-state behaviour the editor expects: at the end of each solve
     * iteration the rigid constraints are exactly satisfied and the body sits at whatever pose
     * on the rigid manifold is closest to the spring target. Springs running last would leave
     * the body at the spring's preference and let the rigid constraints visibly violate.
     *
     * <p>Returns {@code false} if {@code element} isn't in this session's active set (the seed
     * was passed but {@code collect} didn't actually visit it — shouldn't happen under normal
     * use, but indicates a caller bug if it does).
     */
    public boolean addCentreSpring(CircuitElement element, double targetX, double targetY,
                                   double compliance) {
        Body b = bodyById.get(element.getId());
        if (b == null) {
            return false;
        }
        constraints.add(0, new SpringConstraint(
                AnchorPoint.atCentre(b), new Vec2(targetX, targetY), compliance));
        return true;
    }

    /**
     * Forces {@code element}'s body to {@code invMassT = invMassR = 0} (fully immobilised) at
     * its current pose. Used by the drag aggregator's contention policy: when two gestures
     * target the same element in one flush, that element is locked for the solve and neither gesture wins.
     * Other bodies in the same connected sub-graph can still move under their own springs /
     * rigid edges, but this body acts as an anchor.
     *
     * <p>Returns {@code false} if {@code element} isn't in this session's active set.
     */
    public boolean lockBodyInPlace(CircuitElement element) {
        Body b = bodyById.get(element.getId());
        if (b == null) {
            return false;
        }
        b.setInvMassT(0.0);
        b.setInvMassR(0.0);
        return true;
    }

    /** Runs the solver with the default config. */
    public SolverResult solve() {
        return solve(DEFAULT_CONFIG);
    }

    /** Runs the solver with the supplied config. */
    public SolverResult solve(SolverConfig config) {
        return Solver.solve(config, constraints);
    }

    /**
     * Writes each body's final pose back to its domain entity. For components, the centre
     * {@link PositionInfo} and {@link RotationInfo} are updated and every owned internal node's
     * world position is re-stamped from the body's pose × the node's local offset. For free
     * nodes, only the node's {@link PositionInfo} is updated.
     *
     * <p>Each mutated element is fed through {@link ServerWorld#getLevel()}'s
     * {@code notifyElementChanged} so the standard sync pipeline replicates the move to client
     * mirrors. Unmoved bodies skip the notify step.
     */
    public void writeback() {
        for (Map.Entry<UUID, Body> e : bodyById.entrySet()) {
            UUID id = e.getKey();
            Body body = e.getValue();
            CircuitElement el = elementById.get(id);
            if (el == null) continue;
            if (el instanceof CircuitComponent comp) {
                writebackComponent(comp, body);
            } else if (el instanceof CircuitNode node) {
                writebackFreeNode(node, body);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Sub-graph collection
    // ---------------------------------------------------------------------

    private void collect(CircuitElement seed) {
        if (seed == null) {
            return;
        }
        if (constraintsFinalised) {
            throw new IllegalStateException(
                    "cannot collect more seeds after constraints have been finalised");
        }
        if (!visited.add(seed)) {
            return; // already covered by a prior collect on this session
        }

        Deque<CircuitElement> queue = new ArrayDeque<>();
        queue.add(seed);

        while (!queue.isEmpty()) {
            CircuitElement el = queue.poll();
            if (el instanceof CircuitComponent comp) {
                ensureComponentBody(comp);
                for (CircuitNode port : comp.getPorts().values()) {
                    if (port == null) continue;
                    if (port.getComponents().size() > 1) {
                        sharedPortsByNodeId.putIfAbsent(port.getId(), port);
                        for (CircuitComponent owner : port.getComponents()) {
                            if (owner != comp && visited.add(owner)) {
                                queue.add(owner);
                            }
                        }
                    }
                    for (CircuitEdge edge : new ArrayList<>(port.getConnection())) {
                        if (edge.hasComponent()) {
                            continue;
                        }
                        if (edgesToConstrain.add(edge)) {
                            enqueueOtherEndpoint(edge, port, queue, visited);
                        }
                    }
                }
            } else if (el instanceof CircuitNode node) {
                if (node.getComponents().isEmpty()) {
                    ensureFreeNodeBody(node);
                    for (CircuitEdge edge : new ArrayList<>(node.getConnection())) {
                        if (edge.hasComponent()) continue;
                        if (edgesToConstrain.add(edge)) {
                            enqueueOtherEndpoint(edge, node, queue, visited);
                        }
                    }
                } else {
                    if (node.getComponents().size() > 1) {
                        sharedPortsByNodeId.putIfAbsent(node.getId(), node);
                    }
                    for (CircuitComponent owner : node.getComponents()) {
                        if (visited.add(owner)) {
                            queue.add(owner);
                        }
                    }
                }
            }
        }
    }

    /**
     * Emits constraints for every edge and shared port discovered across all {@link #collect}
     * calls so far. Subsequent calls are idempotent. Must run before {@link #solve} or any other
     * accessor that returns the constraint list.
     */
    private void finaliseConstraints() {
        if (constraintsFinalised) {
            return;
        }
        constraintsFinalised = true;
        for (CircuitEdge edge : edgesToConstrain) {
            emitEdgeConstraint(edge);
        }
        for (CircuitNode port : sharedPortsByNodeId.values()) {
            emitSharedPortConstraints(port);
        }
    }

    private static void enqueueOtherEndpoint(CircuitEdge edge, CircuitNode here,
                                              Deque<CircuitElement> queue,
                                              Set<CircuitElement> visited) {
        CircuitNode other = edge.getOther(here);
        if (other != null && visited.add(other)) {
            queue.add(other);
        }
    }

    // ---------------------------------------------------------------------
    // Body factories
    // ---------------------------------------------------------------------

    private void ensureComponentBody(CircuitComponent comp) {
        UUID id = comp.getId();
        if (bodyById.containsKey(id)) {
            return;
        }
        PositionInfo centre = comp.getInfo(AllElementInfos.POSITION);
        double cx = centre != null ? centre.getX() : 0.0;
        double cy = centre != null ? centre.getY() : 0.0;
        RotationInfo rot = comp.getInfo(AllElementInfos.ROTATION);
        double angle = rot != null ? rot.getAngle() : 0.0;

        LockState eff = comp.effectiveLockState(1e-6);
        Body body = newBodyForLock(id, cx, cy, angle, eff);
        bodyById.put(id, body);
        elementById.put(id, comp);

        // Snapshot the local-frame offset of every internal node so writeback can rigidly move
        // them with the body. Multi-owner ports are skipped here — they'd otherwise be stamped
        // twice (once per owning component), with the second stamp clobbering the first; their
        // world position is enforced by the pin-joint constraint between owners' anchors and
        // written back via whichever component's body the BFS discovered first.
        Vec2 centreVec = new Vec2(cx, cy);
        double cosNeg = Math.cos(-angle);
        double sinNeg = Math.sin(-angle);
        Map<UUID, Vec2> offsets = new LinkedHashMap<>();
        for (CircuitNode internal : comp.getNodes()) {
            if (internal == null) continue;
            if (internal.getComponents().size() > 1) continue;
            PositionInfo nodePos = internal.getInfo(AllElementInfos.POSITION);
            if (nodePos == null) continue;
            Vec2 world = new Vec2(nodePos.getX(), nodePos.getY());
            Vec2 local = world.sub(centreVec).rotate(cosNeg, sinNeg);
            offsets.put(internal.getId(), local);
            internalNodeById.put(internal.getId(), internal);
        }
        internalLocalOffsets.put(id, offsets);
    }

    private void ensureFreeNodeBody(CircuitNode node) {
        UUID id = node.getId();
        if (bodyById.containsKey(id)) {
            return;
        }
        PositionInfo p = node.getInfo(AllElementInfos.POSITION);
        double x = p != null ? p.getX() : 0.0;
        double y = p != null ? p.getY() : 0.0;

        // Free nodes are point-like (no rotation DOF): invMassR = 0 unconditionally. invMassT is
        // 0 when the node is isFixed (player-set lock on a free node), else 1 (freely draggable).
        Body body;
        if (p != null && p.isFixed()) {
            body = Body.locked(id, new Vec2(x, y));
        } else {
            body = Body.positionFree(id, new Vec2(x, y));
        }
        bodyById.put(id, body);
        elementById.put(id, node);
    }

    /**
     * Builds a {@link Body} whose inverse inertias and rotation pivot match the supplied lock
     * state. The body's centre starts at {@code (cx, cy)} with the supplied angle.
     */
    private static Body newBodyForLock(UUID id, double cx, double cy, double angle,
                                        LockState eff) {
        Vec2 centre = new Vec2(cx, cy);
        LockMode mode = eff.mode();
        Body body = switch (mode) {
            case FREE -> Body.free(id, centre);
            case POSITION_FREE -> Body.positionFree(id, centre);
            case ROTATION_FREE -> {
                Vec2 pivot = eff.pivotValid() ? new Vec2(eff.pivotX(), eff.pivotY()) : centre;
                yield Body.rotationFree(id, centre, pivot);
            }
            case LOCKED -> Body.locked(id, centre);
        };
        body.setAngle(angle);
        return body;
    }

    // ---------------------------------------------------------------------
    // Constraint factories
    // ---------------------------------------------------------------------

    private void emitEdgeConstraint(CircuitEdge edge) {
        RigidityInfo rigid = edge.getInfo(AllElementInfos.RIGIDITY);
        if (rigid == null || !rigid.isRigid()) {
            return; // flexible edges contribute nothing
        }
        AnchorPoint a = anchorFor(edge.getStart());
        AnchorPoint b = anchorFor(edge.getEnd());
        if (a == null || b == null) {
            return;
        }
        constraints.add(DistanceConstraint.atCurrentLength(a, b));
    }

    private void emitSharedPortConstraints(CircuitNode port) {
        List<AnchorPoint> anchors = new ArrayList<>();
        for (CircuitComponent owner : port.getComponents()) {
            AnchorPoint anchor = anchorOnComponent(owner, port);
            if (anchor != null) {
                anchors.add(anchor);
            }
        }
        if (anchors.size() < 2) {
            return; // not actually shared in this session
        }
        // Star topology: anchor 0 ↔ anchor k for k=1..N-1. N-1 pin joints, all of which want
        // anchor k to coincide with anchor 0 in world space — the solver handles the global
        // agreement via repeated relaxation.
        AnchorPoint hub = anchors.get(0);
        for (int i = 1; i < anchors.size(); i++) {
            constraints.add(new PinJointConstraint(hub, anchors.get(i)));
        }
    }

    /**
     * Returns an {@link AnchorPoint} representing the world location {@code node} occupies, on
     * whichever body in this session corresponds to it: the owning component's body for a port
     * node, or the node's own body for a free node.
     */
    private AnchorPoint anchorFor(CircuitNode node) {
        if (node == null) {
            return null;
        }
        if (node.getComponents().isEmpty()) {
            // Free node: anchor at its own body's centre.
            Body body = bodyById.get(node.getId());
            if (body == null) return null;
            return AnchorPoint.atCentre(body);
        }
        // Port node: anchor on the first owning component's body at the node's local offset.
        // For multi-owner ports the pin joint emitted in the post-pass forces every owner's
        // anchor to coincide; the edge constraint can pick any one of them.
        CircuitComponent owner = node.getComponents().iterator().next();
        return anchorOnComponent(owner, node);
    }

    /**
     * Computes the local-frame offset of {@code node} on {@code owner}'s body and returns an
     * {@link AnchorPoint} for it. The offset is back-computed from the node's current world
     * position and the owner's current pose: {@code local = R(-angle) * (node_world -
     * owner_centre)}. Back-computing keeps the anchor consistent with whatever the placement
     * pipeline stamped, even for ports without a registered {@link ComponentAnchorRegistry} entry.
     */
    private AnchorPoint anchorOnComponent(CircuitComponent owner, CircuitNode node) {
        Body body = bodyById.get(owner.getId());
        if (body == null) {
            ensureComponentBody(owner);
            body = bodyById.get(owner.getId());
            if (body == null) return null;
        }
        PositionInfo nodePos = node.getInfo(AllElementInfos.POSITION);
        if (nodePos == null) {
            // Without a node position we can't compute a local offset; use centre as the safe default.
            return AnchorPoint.atCentre(body);
        }
        Vec2 worldNode = new Vec2(nodePos.getX(), nodePos.getY());
        Vec2 centre = body.position();
        double angle = body.angle();
        double cos = Math.cos(-angle);
        double sin = Math.sin(-angle);
        Vec2 local = worldNode.sub(centre).rotate(cos, sin);
        return new AnchorPoint(body, local);
    }

    // ---------------------------------------------------------------------
    // Writeback
    // ---------------------------------------------------------------------

    private void writebackComponent(CircuitComponent comp, Body body) {
        Vec2 newPos = body.position();
        double newAngle = body.angle();
        PositionInfo centre = comp.getInfo(AllElementInfos.POSITION);
        boolean centreMoved = false;
        if (centre == null) {
            centre = new PositionInfo();
            comp.setInfo(AllElementInfos.POSITION, centre);
            centreMoved = true;
        }
        if (centre.getX() != newPos.x() || centre.getY() != newPos.y()) {
            centre.set(newPos.x(), newPos.y());
            centreMoved = true;
        }
        RotationInfo rot = comp.getInfo(AllElementInfos.ROTATION);
        boolean angleMoved = false;
        if (rot != null && rot.getAngle() != newAngle) {
            rot.setAngle(newAngle);
            angleMoved = true;
        }
        if (!centreMoved && !angleMoved) {
            // Body wasn't perturbed — skip the internal-node re-stamp and the notify.
            return;
        }
        restampInternals(comp, body);
        notifyChanged(comp);
    }

    private void writebackFreeNode(CircuitNode node, Body body) {
        Vec2 newPos = body.position();
        PositionInfo p = node.getInfo(AllElementInfos.POSITION);
        if (p == null) {
            p = new PositionInfo();
            node.setInfo(AllElementInfos.POSITION, p);
        }
        if (p.getX() == newPos.x() && p.getY() == newPos.y()) {
            return;
        }
        // Defensive: don't overwrite an isFixed=true free node even if the solver thinks it
        // moved (it shouldn't — invMassT=0 — but belt-and-braces).
        if (p.isFixed()) {
            return;
        }
        p.set(newPos.x(), newPos.y());
        notifyChanged(node);
    }

    /**
     * Applies {@code body}'s post-solve pose to each of {@code comp}'s internal nodes via the
     * local-frame offsets snapshotted at session-build time. The snapshots are independent of
     * {@link ComponentAnchorRegistry}: they capture whatever each node's world position was when
     * the session was built, so the rigid-body assumption holds even for nodes that were placed
     * outside the registry (test fixtures, hand-edited layouts) or that the player moved via the
     * panel between gestures.
     *
     * <p>Multi-owner ports are intentionally skipped at snapshot time too (see
     * {@link #ensureComponentBody}), so they're absent from {@code offsets} and never stamped
     * twice across owners.
     */
    private void restampInternals(CircuitComponent comp, Body body) {
        Map<UUID, Vec2> offsets = internalLocalOffsets.get(comp.getId());
        if (offsets == null) {
            return;
        }
        for (Map.Entry<UUID, Vec2> e : offsets.entrySet()) {
            CircuitNode internal = internalNodeById.get(e.getKey());
            if (internal == null) {
                continue;
            }
            Vec2 world = body.localToWorld(e.getValue());
            PositionInfo p = internal.getInfo(AllElementInfos.POSITION);
            if (p == null) {
                p = new PositionInfo();
                internal.setInfo(AllElementInfos.POSITION, p);
            }
            if (p.getX() != world.x() || p.getY() != world.y()) {
                p.set(world.x(), world.y());
                notifyChanged(internal);
            }
        }
    }

    private void notifyChanged(CircuitElement el) {
        if (el.getWorld() != null) {
            el.getWorld().getLevel().notifyElementChanged(el);
        }
    }
}
