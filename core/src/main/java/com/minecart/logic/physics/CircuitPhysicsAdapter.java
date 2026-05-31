package com.minecart.logic.physics;

import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerWorld;
import com.minecart.physics.SolverResult;
import com.minecart.physics.Vec2;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.LockMode;
import com.minecart.variant.info.LockState;
import com.minecart.variant.info.PositionInfo;
import com.minecart.variant.info.RigidityInfo;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Public façade between the cascade engine / drag handlers and {@link SolveSession}. Two entry
 * points, one per gesture kind:
 *
 * <ul>
 *   <li>{@link #translate(ServerWorld, CircuitElement, double, double)} — drag (or panel edit) that
 *       moves the seed by a delta. The seed is pinned at its post-translation pose; the solver
 *       propagates motion across the kinematic graph.</li>
 *   <li>{@link #rotate(ServerWorld, CircuitComponent, double, double, double)} — rotation gesture.
 *       The seed is pinned at its post-rotation pose; the solver propagates motion the same way.</li>
 * </ul>
 *
 * <p>Both entry points are <em>kinematic</em>: they enforce the gesture as a hard pose change on
 * the seed and let the constraint graph absorb the consequences. Locked bodies elsewhere in the
 * chain refuse to move along their forbidden DOFs (via {@code invMass = 0}), so the user's
 * "rotation lock always wins" intent is encoded directly in the body factory, not in the gesture
 * code.
 *
 * <h2>Out of scope here</h2>
 * Topological changes (combine, port swap, edge rewire) and the strict-lock preflight ("does this
 * gesture even get to run?") are handled by the cascade engine and the per-handler guards. This
 * adapter assumes the caller has already decided the gesture is legal and just needs to be
 * propagated.
 */
public final class CircuitPhysicsAdapter {

    /**
     * Default XPBD compliance for cursor-pull springs in the drag batch path. {@code 0.001} is
     * "very stiff" — single-drag visually behaves like a hard pin, multi-drag against rigid
     * couplings lets the rigid edges win. See {@code SpringConstraint} javadoc for the full
     * compliance scale; this constant is the editor's default and can be re-tuned later.
     */
    public static final double DEFAULT_DRAG_COMPLIANCE = 0.001;

    private CircuitPhysicsAdapter() {}

    /**
     * Translates {@code seed} by {@code (dx, dy)} and propagates the motion across its
     * kinematically-reachable sub-graph (rigid edges, multi-owner ports).
     *
     * @return the solver result; {@code converged == false} indicates the chain settled into a
     *         best-effort pose rather than a fully-satisfied one (e.g. the gesture pulled
     *         against an unreachable target). Callers may use this signal to surface UI hints
     *         but the world is in any case left in the best-effort pose.
     */
    public static SolverResult translate(ServerWorld world, CircuitElement seed,
                                          double dx, double dy) {
        if (world == null || seed == null) {
            return new SolverResult(0, 0.0, true);
        }
        if (dx == 0.0 && dy == 0.0) {
            return new SolverResult(0, 0.0, true);
        }
        SolveSession session = SolveSession.build(world, seed);
        if (!session.lockSeedTranslated(seed, dx, dy)) {
            return new SolverResult(0, 0.0, true);
        }
        SolverResult result = session.solve();
        session.writeback();
        return result;
    }

    /**
     * Rotates {@code seed} by {@code deltaRadians} around {@code (pivotX, pivotY)} and propagates
     * the motion the same way as {@link #translate}. See class javadoc for the "locks always win"
     * semantics — bodies along the chain that are rotation-locked simply don't rotate and the
     * gesture lands partially.
     */
    public static SolverResult rotate(ServerWorld world, CircuitComponent seed,
                                       double pivotX, double pivotY, double deltaRadians) {
        if (world == null || seed == null) {
            return new SolverResult(0, 0.0, true);
        }
        if (deltaRadians == 0.0) {
            return new SolverResult(0, 0.0, true);
        }
        SolveSession session = SolveSession.build(world, seed);
        if (!session.lockSeedRotated(seed, pivotX, pivotY, deltaRadians)) {
            return new SolverResult(0, 0.0, true);
        }
        SolverResult result = session.solve();
        session.writeback();
        return result;
    }

    /**
     * Convenience for free-node drags: dispatches to {@link #translate} after checking that the
     * caller actually passed a free node (port nodes need to go through the component-translate
     * path so anchor offsets stay consistent).
     */
    public static SolverResult translateFreeNode(ServerWorld world, CircuitNode node,
                                                  double dx, double dy) {
        if (node == null || !node.getComponents().isEmpty()) {
            return new SolverResult(0, 0.0, true);
        }
        return translate(world, node, dx, dy);
    }

    /**
     * Applies an edge drag sample to the edge's effective lock mode.
     * The edge contributes lock constraints through {@link SolveSession}; the drag itself is just
     * a spring on the nearest endpoint anchor. Rigidity remains an independent distance constraint.
     */
    public static SolverResult dragEdgeAnchor(ServerWorld world, CircuitEdge edge,
                                              double targetX, double targetY,
                                              boolean hasLocalAnchor,
                                              double localAnchorX, double localAnchorY) {
        if (world == null || edge == null) {
            return new SolverResult(0, 0.0, true);
        }
        LockState eff = edge.effectiveLockState(1e-6);
        LockMode mode = eff.mode();
        if (mode == LockMode.LOCKED) {
            notifyEdgeAuthority(world, edge);
            return new SolverResult(0, 0.0, true);
        }

        CircuitNode dragged = chooseDraggedEndpoint(edge, hasLocalAnchor, localAnchorX);
        CircuitElement movable = endpointMovable(dragged);
        if (dragged == null || movable == null) {
            notifyEdgeAuthority(world, edge);
            return new SolverResult(0, 0.0, true);
        }

        SolveSession session = SolveSession.buildMulti(world, java.util.List.of(movable));
        RigidityInfo rigid = edge.getInfo(AllElementInfos.RIGIDITY);
        boolean isRigid = rigid != null && rigid.isRigid();
        if (!isRigid && mode == LockMode.ORIENTED) {
            CircuitNode other = edge.getOther(dragged);
            CircuitElement otherMovable = endpointMovable(other);
            if (otherMovable != null) {
                session.lockBodyInPlace(otherMovable);
            }
        }

        Vec2 endpointTarget = edgeEndpointTarget(edge, dragged, targetX, targetY,
                hasLocalAnchor, localAnchorX, localAnchorY);
        if (!session.addNodeSpring(dragged, endpointTarget.x(), endpointTarget.y(), DEFAULT_DRAG_COMPLIANCE)) {
            notifyEdgeAuthority(world, edge);
            return new SolverResult(0, 0.0, true);
        }
        SolverResult result = session.solve();
        session.writeback();
        world.getLevel().notifyElementChanged(edge);
        return result;
    }

    /**
     * Multi-drag entry point: applies every gesture in {@code gestures} as a soft-spring pull
     * toward its target pose, running a single unified physics solve over the union sub-graph.
     * This is the drag aggregator's hot path — replaces the hard-pin-per-gesture model with
     * a softer one that resolves contention via the constraint graph rather than via a
     * separately-tracked lease.
     *
     * <h2>Contention policy</h2>
     * If two or more distinct {@link DragGesture#gestureId()}s target the same element in the
     * same batch, that element is treated as <em>immobilised</em> for this solve: its body is
     * locked at its current authoritative pose ({@code invMassT = invMassR = 0}), no spring is
     * attached, and other gestures in the same connected sub-graph have to route around it. Per
     * the user's "tie = both lose" tie-breaker policy.
     *
     * <h2>Coalescing</h2>
     * Multiple gestures with the same {@code (gestureId, target)} pair coalesce to the
     * <em>latest</em> one (insertion order through {@code gestures}). Callers should pass the
     * latest cursor sample per gesture; redundant earlier samples in the same flush are harmless.
     *
     * <h2>Disjoint sub-graphs</h2>
     * Gestures whose target sub-graphs don't overlap are solved together in one session, but the
     * constraint graph is naturally partitioned: corrections from one sub-graph don't propagate
     * across the gap. The same solve loop handles both cases without special-casing.
     *
     * @return the solver result for the unified solve; mostly informational
     */
    public static SolverResult applyDragBatch(ServerWorld world, Collection<DragGesture> gestures) {
        if (world == null || gestures == null || gestures.isEmpty()) {
            return new SolverResult(0, 0.0, true);
        }

        // Edge drags are first-class edge gestures rather than decomposed client-side endpoint
        // moves, so the edge's own LockInfo can decide how the endpoint movables transform.
        Map<UUID, Set<UUID>> distinctEdgeGesturesPerTarget = new LinkedHashMap<>();
        Map<UUID, CircuitEdge> edgeTargetById = new LinkedHashMap<>();
        Map<TargetGestureKey, DragGesture> latestPerEdgeTargetGesture = new LinkedHashMap<>();

        // For each non-edge target element, collect the distinct gesture ids and the latest gesture per
        // (gestureId). Linked maps preserve insertion order for deterministic spring ordering.
        Map<UUID, Set<UUID>> distinctGesturesPerTarget = new LinkedHashMap<>();
        Map<UUID, CircuitElement> targetById = new LinkedHashMap<>();
        Map<TargetGestureKey, DragGesture> latestPerTargetGesture = new LinkedHashMap<>();
        for (DragGesture g : gestures) {
            if (g == null || g.target() == null) continue;
            UUID targetId = g.target().getId();
            if (g.target() instanceof CircuitEdge edge) {
                edgeTargetById.putIfAbsent(targetId, edge);
                distinctEdgeGesturesPerTarget
                        .computeIfAbsent(targetId, k -> new LinkedHashSet<>())
                        .add(g.gestureId());
                latestPerEdgeTargetGesture.put(new TargetGestureKey(targetId, g.gestureId()), g);
                continue;
            }
            targetById.putIfAbsent(targetId, g.target());
            distinctGesturesPerTarget
                    .computeIfAbsent(targetId, k -> new LinkedHashSet<>())
                    .add(g.gestureId());
            // Last-write-wins per (target, gesture) — coalesces streaming samples.
            latestPerTargetGesture.put(new TargetGestureKey(targetId, g.gestureId()), g);
        }
        if (targetById.isEmpty() && edgeTargetById.isEmpty()) {
            return new SolverResult(0, 0.0, true);
        }

        SolverResult result = new SolverResult(0, 0.0, true);

        Set<UUID> contendedEdgeTargetIds = new LinkedHashSet<>();
        for (Map.Entry<UUID, Set<UUID>> e : distinctEdgeGesturesPerTarget.entrySet()) {
            if (e.getValue().size() >= 2) {
                contendedEdgeTargetIds.add(e.getKey());
            }
        }
        for (DragGesture g : latestPerEdgeTargetGesture.values()) {
            if (contendedEdgeTargetIds.contains(g.target().getId())) {
                notifyEdgeAuthority(world, (CircuitEdge) g.target());
                continue;
            }
            result = dragEdgeAnchor(world, (CircuitEdge) g.target(), g.targetX(), g.targetY(),
                    g.hasAnchorLocal(), g.anchorLocalX(), g.anchorLocalY());
        }

        if (targetById.isEmpty()) {
            return result;
        }

        Set<UUID> contendedTargetIds = new LinkedHashSet<>();
        for (Map.Entry<UUID, Set<UUID>> e : distinctGesturesPerTarget.entrySet()) {
            if (e.getValue().size() >= 2) {
                contendedTargetIds.add(e.getKey());
            }
        }

        // Single multi-seed session: union BFS from every target builds bodies + rigid edges
        // across all involved sub-graphs.
        SolveSession session = SolveSession.buildMulti(world, targetById.values());

        // Immobilise contended targets first — this also ensures any spring that would have
        // attached to them is skipped below.
        for (UUID contendedId : contendedTargetIds) {
            session.lockBodyInPlace(targetById.get(contendedId));
        }

        // One spring per uncontended (target, gesture). Spring deduplication for the streaming-
        // sample case happened above via latestPerTargetGesture.
        for (DragGesture g : latestPerTargetGesture.values()) {
            if (contendedTargetIds.contains(g.target().getId())) {
                continue;
            }
            if (g.hasAnchorLocal()) {
                session.addAnchorSpring(g.target(), new Vec2(g.anchorLocalX(), g.anchorLocalY()),
                        g.targetX(), g.targetY(), DEFAULT_DRAG_COMPLIANCE);
            } else {
                session.addCentreSpring(g.target(), g.targetX(), g.targetY(), DEFAULT_DRAG_COMPLIANCE);
            }
        }

        result = session.solve();
        session.writeback();
        return result;
    }

    private static CircuitNode chooseDraggedEndpoint(CircuitEdge edge, boolean hasLocalAnchor,
                                                     double localAnchorX) {
        if (edge == null) {
            return null;
        }
        if (hasLocalAnchor && localAnchorX > 0.0) {
            return edge.getEnd();
        }
        return edge.getStart();
    }

    private static Vec2 edgeEndpointTarget(CircuitEdge edge, CircuitNode dragged,
                                           double targetX, double targetY,
                                           boolean hasLocalAnchor,
                                           double localAnchorX, double localAnchorY) {
        if (!hasLocalAnchor || edge == null || dragged == null) {
            return new Vec2(targetX, targetY);
        }
        PositionInfo sp = edge.getStart() != null ? edge.getStart().getInfo(AllElementInfos.POSITION) : null;
        PositionInfo ep = edge.getEnd() != null ? edge.getEnd().getInfo(AllElementInfos.POSITION) : null;
        PositionInfo dp = dragged.getInfo(AllElementInfos.POSITION);
        if (sp == null || ep == null || dp == null) {
            return new Vec2(targetX, targetY);
        }
        double cx = (sp.getX() + ep.getX()) * 0.5;
        double cy = (sp.getY() + ep.getY()) * 0.5;
        double angle = Math.atan2(ep.getY() - sp.getY(), ep.getX() - sp.getX());
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double grabbedX = cx + localAnchorX * cos - localAnchorY * sin;
        double grabbedY = cy + localAnchorX * sin + localAnchorY * cos;
        return new Vec2(targetX + (dp.getX() - grabbedX), targetY + (dp.getY() - grabbedY));
    }

    private static CircuitElement endpointMovable(CircuitNode endpoint) {
        if (endpoint == null) {
            return null;
        }
        CircuitComponent owner = endpoint.getComponent();
        if (owner != null) {
            return owner.isPort(endpoint) ? owner : null;
        }
        return endpoint;
    }

    private static void notifyEdgeAuthority(ServerWorld world, CircuitEdge edge) {
        if (world == null || edge == null) {
            return;
        }
        world.getLevel().notifyElementChanged(edge);
        notifyEndpointAuthority(world, edge.getStart());
        notifyEndpointAuthority(world, edge.getEnd());
    }

    private static void notifyEndpointAuthority(ServerWorld world, CircuitNode endpoint) {
        if (endpoint == null) {
            return;
        }
        world.getLevel().notifyElementChanged(endpoint);
        CircuitComponent owner = endpoint.getComponent();
        if (owner != null) {
            world.getLevel().notifyElementChanged(owner);
        }
    }

    /**
     * Composite key for the streaming-coalesce map in {@link #applyDragBatch}. Two payloads with
     * the same {@code (targetId, gestureId)} pair collapse to the latest one; distinct pairs are
     * kept separately (two gestures on the same target → contention; two targets for one gesture
     * → independent springs).
     */
    private record TargetGestureKey(UUID targetId, UUID gestureId) {}
}
