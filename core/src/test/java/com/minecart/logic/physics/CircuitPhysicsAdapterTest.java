package com.minecart.logic.physics;

import com.minecart.elements.component.BJTransistor;
import com.minecart.elements.edge.Resistor;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllComponents;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.LockInfo;
import com.minecart.variant.info.LockMode;
import com.minecart.variant.info.PositionInfo;
import com.minecart.variant.info.RigidityInfo;
import com.minecart.variant.info.RotationInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the physics adapter ({@link CircuitPhysicsAdapter}) — the bridge between
 * the domain layer's components / edges / nodes and the {@code com.minecart.physics} solver.
 *
 * <p>Each test builds a small topology, calls the adapter once (translate or rotate the seed),
 * and asserts the expected post-solve poses for both the seed and the rest of the sub-graph.
 * Together they cover the behaviour switches we care about for the editor's cascading drag:
 *
 * <ul>
 *   <li>Lone seed (no constraints) — only the seed moves; the rest of the world is undisturbed.</li>
 *   <li>Rigid edge between two free nodes — both ends move rigidly as a unit.</li>
 *   <li>Flexible edge (default) — the connected free node does NOT follow when the seed moves;
 *       the edge just stretches visually.</li>
 *   <li>Rigid edge between two components — dragging one drags the other along the constraint.</li>
 *   <li>Rotation gesture on the seed — internal anchors orbit the pivot rigidly.</li>
 *   <li>Locked body in the chain — when the chain pulls on a locked body, the locked body refuses
 *       to move and the unsatisfiable residual settles into a best-effort pose.</li>
 * </ul>
 */
class CircuitPhysicsAdapterTest {

    private static final double EPS = 1e-6;

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static void place(CircuitNode n, double x, double y) {
        PositionInfo p = n.getInfo(AllElementInfos.POSITION);
        if (p == null) {
            n.setInfo(AllElementInfos.POSITION, new PositionInfo(x, y));
        } else {
            p.set(x, y);
        }
    }

    private static BJTransistor placeBJT(ServerWorld w, double cx, double cy) {
        BJTransistor bjt = w.createComponent(AllComponents.BJ_TRANSISTOR);
        bjt.setInfo(AllElementInfos.POSITION, new PositionInfo(cx, cy));
        bjt.setInfo(AllElementInfos.ROTATION, new RotationInfo(0.0));
        place(bjt.getPort(0), cx - 1.0, cy);
        place(bjt.getPort(1), cx + 1.0, cy + 0.5);
        place(bjt.getPort(2), cx + 1.0, cy - 0.5);
        place(bjt.getCenter(), cx, cy);
        return bjt;
    }

    private static void markRigid(CircuitEdge edge) {
        RigidityInfo r = edge.getInfo(AllElementInfos.RIGIDITY);
        if (r == null) {
            r = new RigidityInfo();
            edge.setInfo(AllElementInfos.RIGIDITY, r);
        }
        r.setRigid(true);
    }

    private static double x(CircuitNode n) {
        return n.getInfo(AllElementInfos.POSITION).getX();
    }

    private static double y(CircuitNode n) {
        return n.getInfo(AllElementInfos.POSITION).getY();
    }

    // ---------------------------------------------------------------------
    // Translation: lone seed
    // ---------------------------------------------------------------------

    @Test
    void translate_loneComponent_movesOnlyTheComponent() {
        // With no rigid edges and no other components reachable, the seed body is the entire
        // active set. Translating it just shifts the component centre + internal nodes.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);

        CircuitPhysicsAdapter.translate(w, bjt, 3.0, 5.0);

        assertEquals(3.0, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(5.0, bjt.getInfo(AllElementInfos.POSITION).getY(), EPS);
        // Internal nodes follow rigidly (back-computed local offsets unchanged).
        assertEquals(2.0, x(bjt.getPort(0)), EPS); // was (-1, 0) + delta = (2, 5)
        assertEquals(5.0, y(bjt.getPort(0)), EPS);
        assertEquals(4.0, x(bjt.getPort(1)), EPS);
        assertEquals(5.5, y(bjt.getPort(1)), EPS);
    }

    // ---------------------------------------------------------------------
    // Translation: rigid vs flexible edge propagation
    // ---------------------------------------------------------------------

    @Test
    void translate_freeNode_withRigidEdge_pullsTheOtherEndpoint() {
        // Two free nodes joined by a rigid wire of length 1. Translating one pulls the other
        // along the current wire length (PBD distance constraint). Note: PBD chooses the
        // shortest correction direction, so b is pulled toward a — ending one unit "behind"
        // a along the drag direction, not on the far side. This is the standard rigid-bar
        // trailing behaviour: imagine pulling a stick by one end, the other end follows.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        place(a, 0.0, 0.0);
        place(b, 1.0, 0.0);
        Resistor wire = w.connect(AllComponents.RESISTOR, a, b);
        assertNotNull(wire);
        markRigid(wire);

        CircuitPhysicsAdapter.translate(w, a, 5.0, 0.0);

        // a was the seed: pinned at its target (5, 0).
        assertEquals(5.0, x(a), EPS);
        assertEquals(0.0, y(a), EPS);
        // b is pulled along the constraint axis toward a, ending at distance 1 from a along
        // the line connecting old-b to new-a. Old line: (1,0) → (5,0), direction = +x. b ends
        // at (5, 0) - 1 * (+x) = (4, 0).
        assertEquals(4.0, x(b), 1e-4);
        assertEquals(0.0, y(b), 1e-4);
        // Sanity: the rigid wire's length is preserved.
        double wireLen = Math.hypot(x(b) - x(a), y(b) - y(a));
        assertEquals(1.0, wireLen, 1e-4);
    }

    @Test
    void translate_freeNode_withFlexibleEdge_doesNotPropagate() {
        // Same topology but the edge stays flexible (default). Dragging a leaves b alone.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        place(a, 0.0, 0.0);
        place(b, 1.0, 0.0);
        Resistor wire = w.connect(AllComponents.RESISTOR, a, b);
        assertNotNull(wire);
        // Note: RigidityInfo is auto-injected (default rigid=false) — no markRigid call.

        CircuitPhysicsAdapter.translate(w, a, 5.0, 0.0);

        assertEquals(5.0, x(a), EPS);
        assertEquals(1.0, x(b), EPS); // unchanged
        assertEquals(0.0, y(b), EPS);
    }

    @Test
    void translate_componentToComponent_throughRigidWire_propagates() {
        // Two BJTransistors wired emitter-to-emitter with a rigid wire. Dragging one shifts the
        // other so the wire length is preserved. With placeBJT()'s layout, the left emitter
        // (port 2) sits at (1, -0.5) and the right emitter at (5, -0.5) when the BJTs are at
        // (0,0) and (4,0) respectively, giving a 4-unit rigid wire.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor left = placeBJT(w, 0.0, 0.0);
        BJTransistor right = placeBJT(w, 4.0, 0.0);
        Resistor wire = w.connect(AllComponents.RESISTOR, left.getPort(2), right.getPort(2));
        assertNotNull(wire);
        markRigid(wire);

        double wireLenBefore = Math.hypot(
                x(right.getPort(2)) - x(left.getPort(2)),
                y(right.getPort(2)) - y(left.getPort(2)));

        CircuitPhysicsAdapter.translate(w, left, 3.0, 0.0);

        assertEquals(3.0, left.getInfo(AllElementInfos.POSITION).getX(), EPS);
        // The right BJT should follow far enough that the rigid wire stays the same length.
        double wireLenAfter = Math.hypot(
                x(right.getPort(2)) - x(left.getPort(2)),
                y(right.getPort(2)) - y(left.getPort(2)));
        assertEquals(wireLenBefore, wireLenAfter, 1e-3);
        // Right BJT must have actually moved (else the wire would've stretched). When left
        // drags +x, the gap between the emitters shrinks below the wire's 4-unit rest length,
        // so the constraint pushes right's emitter (and the body it's anchored on) AWAY along
        // +x to restore the rest length.
        assertTrue(right.getInfo(AllElementInfos.POSITION).getX() > 4.0,
                "right component should be pushed away by the rigid wire");
    }

    @Test
    void dragEdgeAnchor_orientedFlexibleEdge_movesDraggedEndpointAlongLockedDirection() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        place(a, 0.0, 0.0);
        place(b, 2.0, 0.0);
        Resistor edge = w.connect(AllComponents.RESISTOR, a, b);
        assertNotNull(edge);
        edge.setInfo(AllElementInfos.LOCK, new LockInfo(LockMode.ORIENTED, 0.0, 0.0, true));

        CircuitPhysicsAdapter.dragEdgeAnchor(w, edge, 5.0, 3.0,
                true, 1.0, 0.0);

        // Flexible ORIENTED edges preserve direction, not length. The other endpoint stays put
        // while the dragged endpoint slides along the locked horizontal line.
        assertEquals(0.0, x(a), EPS);
        assertEquals(0.0, y(a), EPS);
        assertEquals(5.0, x(b), 0.05);
        assertEquals(0.0, y(b), 0.05);
    }

    @Test
    void dragEdgeAnchor_rigidFreeEdge_preservesLengthWhenCursorIsUnreachable() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        place(a, 0.0, 0.0);
        place(b, 2.0, 0.0);
        Resistor edge = w.connect(AllComponents.RESISTOR, a, b);
        assertNotNull(edge);
        markRigid(edge);

        var result = CircuitPhysicsAdapter.dragEdgeAnchor(w, edge, 20.0, 12.0,
                true, 1.0, 0.0);

        double length = Math.hypot(x(b) - x(a), y(b) - y(a));
        assertTrue(result.converged(), "hard constraints should converge even if the drag spring does not");
        assertEquals(2.0, length, 1e-4, "rigid edge length must have priority over cursor following");
    }

    @Test
    void dragEdgeAnchor_grabbedMiddleDoesNotSnapEndpointToCursor() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        place(a, 0.0, 0.0);
        place(b, 2.0, 0.0);
        Resistor edge = w.connect(AllComponents.RESISTOR, a, b);
        assertNotNull(edge);

        CircuitPhysicsAdapter.dragEdgeAnchor(w, edge, 5.0, 0.0,
                true, 0.0, 0.0);

        assertEquals(4.0, x(a), 0.05, "start endpoint should preserve its offset from the grabbed midpoint");
        assertEquals(0.0, y(a), 0.05);
        assertEquals(2.0, x(b), EPS, "flexible free edge should not drag the other endpoint");
        assertEquals(0.0, y(b), EPS);
    }

    @Test
    void dragEdgeAnchor_pivotedRigidEdge_rotatesGrabbedPointAroundStoredPivot() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        place(a, 1.0, 0.0);
        place(b, 3.0, 0.0);
        Resistor edge = w.connect(AllComponents.RESISTOR, a, b);
        assertNotNull(edge);
        edge.setInfo(AllElementInfos.LOCK, new LockInfo(LockMode.PIVOTED, 0.0, 0.0, true));
        markRigid(edge);

        // Local anchor (1, 0) is the end endpoint at (3, 0). Pulling it to (0, 3)
        // requests a 90-degree rotation around the stored pivot (0, 0). Rigidity separately
        // preserves the two-unit endpoint distance.
        CircuitPhysicsAdapter.dragEdgeAnchor(w, edge, 0.0, 3.0,
                true, 1.0, 0.0);

        double crossWithPivot = x(a) * y(b) - y(a) * x(b);
        double length = Math.hypot(x(b) - x(a), y(b) - y(a));
        assertEquals(0.0, crossWithPivot, 0.02);
        assertEquals(2.0, length, 0.02);
        assertEquals(0.0, x(b), 0.02);
        assertEquals(3.0, y(b), 0.02);
    }

    @Test
    void dragEdgeAnchor_pivotedFlexibleEdge_keepsLineThroughPivotButCanStretch() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        place(a, 1.0, 0.0);
        place(b, 3.0, 0.0);
        Resistor edge = w.connect(AllComponents.RESISTOR, a, b);
        assertNotNull(edge);
        edge.setInfo(AllElementInfos.LOCK, new LockInfo(LockMode.PIVOTED, 0.0, 0.0, true));

        CircuitPhysicsAdapter.dragEdgeAnchor(w, edge, 0.0, 4.0,
                true, 1.0, 0.0);

        // Pivoted flexible edges constrain the edge line through the pivot but do not preserve
        // endpoint distance unless RigidityInfo also contributes a DistanceConstraint.
        double crossWithPivot = x(a) * y(b) - y(a) * x(b);
        assertEquals(0.0, crossWithPivot, 0.05);
        assertEquals(0.0, x(b), 0.1);
        assertEquals(4.0, y(b), 0.1);
    }

    @Test
    void dragEdgeAnchor_lockedEdge_refusesMotion() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        place(a, 0.0, 0.0);
        place(b, 2.0, 0.0);
        Resistor edge = w.connect(AllComponents.RESISTOR, a, b);
        assertNotNull(edge);
        edge.setInfo(AllElementInfos.LOCK, new LockInfo(LockMode.LOCKED, 0.0, 0.0, true));

        CircuitPhysicsAdapter.dragEdgeAnchor(w, edge, 5.0, 3.0,
                false, 0.0, 0.0);

        assertEquals(0.0, x(a), EPS);
        assertEquals(0.0, y(a), EPS);
        assertEquals(2.0, x(b), EPS);
        assertEquals(0.0, y(b), EPS);
    }

    // ---------------------------------------------------------------------
    // Rotation
    // ---------------------------------------------------------------------

    @Test
    void rotate_loneComponent_rotatesAroundPivot_internalsOrbit() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);
        // bjt centre (0,0), port 0 at (-1,0). Rotate 90° CCW around (0,0).
        CircuitPhysicsAdapter.rotate(w, bjt, 0.0, 0.0, Math.PI / 2.0);

        // port 0 swings from (-1, 0) to (0, -1).
        assertEquals(0.0, x(bjt.getPort(0)), 1e-9);
        assertEquals(-1.0, y(bjt.getPort(0)), 1e-9);
        // port 1 swings from (1, 0.5) to (-0.5, 1).
        assertEquals(-0.5, x(bjt.getPort(1)), 1e-9);
        assertEquals(1.0, y(bjt.getPort(1)), 1e-9);
        assertEquals(Math.PI / 2.0, bjt.getInfo(AllElementInfos.ROTATION).getAngle(), 1e-9);
    }

    @Test
    void rotate_aroundOffCentrePivot_translatesAndRotatesTheComponent() {
        // Pivot away from the centre — the component's centre orbits too.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);
        // Pivot at (2, 0). 90° CCW: (0,0) → (2, -2).
        CircuitPhysicsAdapter.rotate(w, bjt, 2.0, 0.0, Math.PI / 2.0);

        assertEquals(2.0, bjt.getInfo(AllElementInfos.POSITION).getX(), 1e-9);
        assertEquals(-2.0, bjt.getInfo(AllElementInfos.POSITION).getY(), 1e-9);
        // port 0 was at (-1, 0), distance 3 from pivot along -x. After 90° CCW: 3 along -y.
        // i.e. (2, -3).
        assertEquals(2.0, x(bjt.getPort(0)), 1e-9);
        assertEquals(-3.0, y(bjt.getPort(0)), 1e-9);
    }

    // ---------------------------------------------------------------------
    // Locks in the chain
    // ---------------------------------------------------------------------

    @Test
    void translate_throughRigidEdge_doesNotMoveALockedDownstreamFreeNode() {
        // Two free nodes joined by a rigid wire. One end is isFixed=true (player-locked node
        // anchor) ⇒ Body.locked ⇒ invMassT=0; the solver can't move it. The dragged seed lands
        // at its target but the locked node stays put — the constraint is left with residual.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        place(a, 0.0, 0.0);
        PositionInfo bp = new PositionInfo(1.0, 0.0);
        bp.setFixed(true);
        b.setInfo(AllElementInfos.POSITION, bp);
        Resistor wire = w.connect(AllComponents.RESISTOR, a, b);
        assertNotNull(wire);
        markRigid(wire);

        CircuitPhysicsAdapter.translate(w, a, 5.0, 0.0);

        // a was the seed: pinned at its target (5, 0).
        assertEquals(5.0, x(a), EPS);
        // b is locked: still at (1, 0), no matter what the constraint asked.
        assertEquals(1.0, x(b), EPS);
        assertEquals(0.0, y(b), EPS);
    }

    @Test
    void translate_throughRigidEdge_doesNotMoveLockedDownstreamComponent() {
        // BJT connected via a rigid wire to a LOCKED BJT. Dragging the free one shouldn't drag
        // the locked one; the locked body has invMass=0 and refuses to move.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor left = placeBJT(w, 0.0, 0.0);
        BJTransistor right = placeBJT(w, 4.0, 0.0);
        LockInfo strict = new LockInfo();
        strict.setMode(LockMode.LOCKED);
        right.setInfo(AllElementInfos.LOCK, strict);

        Resistor wire = w.connect(AllComponents.RESISTOR, left.getPort(2), right.getPort(2));
        assertNotNull(wire);
        markRigid(wire);

        CircuitPhysicsAdapter.translate(w, left, 3.0, 0.0);

        assertEquals(3.0, left.getInfo(AllElementInfos.POSITION).getX(), EPS);
        // Right is LOCKED ⇒ doesn't move even though the rigid wire pulls.
        assertEquals(4.0, right.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(0.0, right.getInfo(AllElementInfos.POSITION).getY(), EPS);
    }

    // ---------------------------------------------------------------------
    // SolveSession internals
    // ---------------------------------------------------------------------

    // ---------------------------------------------------------------------
    // Drag batch (multi-gesture, per-tick aggregator entry point)
    // ---------------------------------------------------------------------

    @Test
    void dragBatch_singleGesture_pullsTargetTowardCursor() {
        // One gesture, no contention, no rigid couplings. Spring at the editor's default
        // compliance should land the target essentially at the cursor (residual << pixel).
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);

        DragGesture g = new DragGesture(java.util.UUID.randomUUID(), bjt, 5.0, 3.0);
        CircuitPhysicsAdapter.applyDragBatch(w, java.util.List.of(g));

        assertEquals(5.0, bjt.getInfo(AllElementInfos.POSITION).getX(), 0.05);
        assertEquals(3.0, bjt.getInfo(AllElementInfos.POSITION).getY(), 0.05);
    }

    @Test
    void dragBatch_disjointGestures_eachReachesItsOwnTarget() {
        // Two unconnected BJTs, each with its own gesture. They should resolve independently.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor a = placeBJT(w, 0.0, 0.0);
        BJTransistor b = placeBJT(w, 10.0, 10.0);

        DragGesture gA = new DragGesture(java.util.UUID.randomUUID(), a, 3.0, 0.0);
        DragGesture gB = new DragGesture(java.util.UUID.randomUUID(), b, 10.0, 15.0);
        CircuitPhysicsAdapter.applyDragBatch(w, java.util.List.of(gA, gB));

        assertEquals(3.0, a.getInfo(AllElementInfos.POSITION).getX(), 0.05);
        assertEquals(0.0, a.getInfo(AllElementInfos.POSITION).getY(), 0.05);
        assertEquals(10.0, b.getInfo(AllElementInfos.POSITION).getX(), 0.05);
        assertEquals(15.0, b.getInfo(AllElementInfos.POSITION).getY(), 0.05);
    }

    @Test
    void dragBatch_sameElementContended_elementIsImmobilised() {
        // Two distinct gestures targeting the same element ⇒ contention ⇒ neither moves. The
        // element stays at its current authoritative pose. Use BJT positioned at (0, 0).
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);

        DragGesture g1 = new DragGesture(java.util.UUID.randomUUID(), bjt, 5.0, 0.0);
        DragGesture g2 = new DragGesture(java.util.UUID.randomUUID(), bjt, -5.0, 0.0);
        CircuitPhysicsAdapter.applyDragBatch(w, java.util.List.of(g1, g2));

        // Body unchanged: contention policy locked it at (0, 0).
        assertEquals(0.0, bjt.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(0.0, bjt.getInfo(AllElementInfos.POSITION).getY(), EPS);
    }

    @Test
    void dragBatch_streamingSameGesture_coalescesToLatestTarget() {
        // Multiple DragGesture instances for the SAME (target, gestureId) ⇒ only the last one's
        // target is honoured. Models the streaming case where the client emits several samples
        // per tick.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor bjt = placeBJT(w, 0.0, 0.0);
        java.util.UUID gid = java.util.UUID.randomUUID();
        DragGesture early = new DragGesture(gid, bjt, 1.0, 0.0);
        DragGesture mid = new DragGesture(gid, bjt, 3.0, 0.0);
        DragGesture latest = new DragGesture(gid, bjt, 7.0, 4.0);

        CircuitPhysicsAdapter.applyDragBatch(w, java.util.List.of(early, mid, latest));

        // Spring honoured the LATEST sample (7, 4), not (1, 0) or (3, 0).
        assertEquals(7.0, bjt.getInfo(AllElementInfos.POSITION).getX(), 0.05);
        assertEquals(4.0, bjt.getInfo(AllElementInfos.POSITION).getY(), 0.05);
    }

    @Test
    void dragBatch_rigidCoupling_propagatesAcrossLinkedElements() {
        // Two BJTs linked by a rigid wire. A single gesture drags the left BJT; the spring pulls
        // it, the rigid edge yanks the right BJT along. Verifies the multi-seed BFS picks up the
        // unseeded element through the constraint graph (the right BJT is in the same sub-graph
        // even though no gesture targets it).
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor left = placeBJT(w, 0.0, 0.0);
        BJTransistor right = placeBJT(w, 4.0, 0.0);
        Resistor wire = w.connect(AllComponents.RESISTOR, left.getPort(2), right.getPort(2));
        assertNotNull(wire);
        markRigid(wire);

        double wireLenBefore = Math.hypot(
                x(right.getPort(2)) - x(left.getPort(2)),
                y(right.getPort(2)) - y(left.getPort(2)));

        DragGesture g = new DragGesture(java.util.UUID.randomUUID(), left, 3.0, 0.0);
        CircuitPhysicsAdapter.applyDragBatch(w, java.util.List.of(g));

        // Left landed near its target.
        assertEquals(3.0, left.getInfo(AllElementInfos.POSITION).getX(), 0.1);
        // Right was pushed AWAY by the rigid wire (the spring pulled left toward right, but the
        // wire's rest length stays the same — right moves out of the way).
        assertTrue(right.getInfo(AllElementInfos.POSITION).getX() > 4.0,
                "right should be pushed away; was at "
                        + right.getInfo(AllElementInfos.POSITION).getX());
        // Rigid wire length preserved.
        double wireLenAfter = Math.hypot(
                x(right.getPort(2)) - x(left.getPort(2)),
                y(right.getPort(2)) - y(left.getPort(2)));
        assertEquals(wireLenBefore, wireLenAfter, 1e-2);
    }

    @Test
    void dragBatch_contentionOnSharedHub_lockedElementBlocksBothDrags() {
        // Two free nodes A and C both linked to a hub element via rigid wires. Two distinct
        // gestures target the SAME hub (contention) ⇒ hub immobilised. A and C are dragged by
        // their own (separate) gestures — should be free to move but constrained by the rigid
        // wires to the hub.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        BJTransistor hub = placeBJT(w, 0.0, 0.0);
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode c = w.createNode(AllComponents.CONNECTION);
        place(a, -3.0, 0.0);
        place(c, 3.0, 0.0);
        Resistor wHubA = w.connect(AllComponents.RESISTOR, hub.getPort(0), a);
        Resistor wHubC = w.connect(AllComponents.RESISTOR, hub.getPort(1), c);
        markRigid(wHubA);
        markRigid(wHubC);

        DragGesture contendA = new DragGesture(java.util.UUID.randomUUID(), hub, 5.0, 5.0);
        DragGesture contendB = new DragGesture(java.util.UUID.randomUUID(), hub, -5.0, -5.0);
        DragGesture dragA = new DragGesture(java.util.UUID.randomUUID(), a, -3.0, 3.0);

        CircuitPhysicsAdapter.applyDragBatch(w, java.util.List.of(contendA, contendB, dragA));

        // Hub didn't move (contended).
        assertEquals(0.0, hub.getInfo(AllElementInfos.POSITION).getX(), EPS);
        assertEquals(0.0, hub.getInfo(AllElementInfos.POSITION).getY(), EPS);
        // A is on the unit circle around hub.getPort(0)'s anchor (a single rigid wire), which
        // sits on the locked hub at (-1, 0). The wire was 2 units long initially (from a=(-3,0)
        // to hub.port0=(-1,0)); dragging A's spring should still preserve that wire length.
        double wireLen = Math.hypot(x(a) - x(hub.getPort(0)), y(a) - y(hub.getPort(0)));
        assertEquals(2.0, wireLen, 0.05);
        // A's spring pulled toward (-3, 3); A should be roughly on the +y side of the hub port.
        assertTrue(y(a) > 0.0, "A should be pulled to +y; got y=" + y(a));
    }

    @Test
    void solveSession_collects_seedAndAllReachableBodies() {
        // Build a small chain: a (free) -- rigid wire -- b (free) -- flexible wire -- c (free).
        // Sub-graph collection should pull in a, b, AND c (flexibility doesn't gate BFS, only
        // constraint emission). Only the rigid wire becomes a DistanceConstraint.
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        CircuitNode c = w.createNode(AllComponents.CONNECTION);
        place(a, 0.0, 0.0);
        place(b, 1.0, 0.0);
        place(c, 2.0, 0.0);
        Resistor ab = w.connect(AllComponents.RESISTOR, a, b);
        Resistor bc = w.connect(AllComponents.RESISTOR, b, c);
        markRigid(ab);
        // bc stays flexible.

        SolveSession session = SolveSession.build(w, a);
        assertTrue(session.contains(a));
        assertTrue(session.contains(b));
        assertTrue(session.contains(c));
        // One rigid edge ⇒ exactly one constraint emitted (the rigid bc would have been a second).
        assertEquals(1, session.constraints().size());
    }
}
