package com.minecart.physics.constraint;

import com.minecart.physics.AnchorPoint;
import com.minecart.physics.Body;
import com.minecart.physics.Solver;
import com.minecart.physics.SolverConfig;
import com.minecart.physics.SolverResult;
import com.minecart.physics.Vec2;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage of {@link PinJointConstraint}'s 2D-vector projection. The pin-joint constraint
 * collapses to a closed-form solve for free / point-like endpoints (one iteration); rigid-body
 * cases with off-centre anchors are validated by running them through the solver.
 */
class PinJointConstraintTest {

    private static final double TIGHT = 1e-9;
    private static final double EPS = 1e-6;

    @Test
    void two_free_point_bodies_converge_in_one_iteration() {
        Body a = Body.free("a", new Vec2(0.0, 0.0));
        Body b = Body.free("b", new Vec2(4.0, 0.0));
        PinJointConstraint c = new PinJointConstraint(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b));

        c.project();
        // Both bodies meet at the midpoint (2, 0) under equal inverse inertia.
        assertEquals(new Vec2(2.0, 0.0), a.position());
        assertEquals(new Vec2(2.0, 0.0), b.position());
        assertEquals(0.0, c.residual(), TIGHT);
    }

    @Test
    void locked_anchor_pins_the_target_world_position() {
        // Locked at (5, 5); free at origin. Pinning them: free moves to (5, 5).
        Body a = Body.locked("a", new Vec2(5.0, 5.0));
        Body b = Body.free("b", new Vec2(0.0, 0.0));
        PinJointConstraint c = new PinJointConstraint(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b));

        c.project();
        assertEquals(new Vec2(5.0, 5.0), a.position());
        assertEquals(new Vec2(5.0, 5.0), b.position());
    }

    @Test
    void both_locked_leaves_residual_unchanged() {
        Body a = Body.locked("a", new Vec2(0.0, 0.0));
        Body b = Body.locked("b", new Vec2(3.0, 4.0));
        PinJointConstraint c = new PinJointConstraint(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b));

        double residual = c.project();
        assertEquals(5.0, residual, TIGHT);
        assertEquals(5.0, c.residual(), TIGHT);
        // No motion.
        assertEquals(Vec2.ZERO, a.position());
        assertEquals(new Vec2(3.0, 4.0), b.position());
    }

    @Test
    void anchors_with_offsets_on_rigid_bodies_converge_to_coincidence() {
        // Body A at origin with anchor offset (1, 0). Body B at (3, 0) with anchor offset (-1, 0).
        // Anchors already coincident at (1, 0)? A: (0,0) + (1,0) = (1,0); B: (3,0) + (-1,0) = (2,0).
        // Off by (1, 0). After solver convergence, both anchors should land at the same world point.
        Body a = Body.free("a", new Vec2(0.0, 0.0));
        Body b = Body.free("b", new Vec2(3.0, 0.0));
        AnchorPoint anchorA = new AnchorPoint(a, new Vec2(1.0, 0.0));
        AnchorPoint anchorB = new AnchorPoint(b, new Vec2(-1.0, 0.0));
        PinJointConstraint c = new PinJointConstraint(anchorA, anchorB);

        SolverResult r = Solver.solve(SolverConfig.defaults(), List.of(c));
        assertTrue(r.converged(), () -> "expected convergence; got " + r);
        // Anchors coincide to within tolerance.
        Vec2 diff = anchorB.worldPosition().sub(anchorA.worldPosition());
        assertEquals(0.0, diff.length(), EPS);
    }

    @Test
    void rotation_free_body_pinned_to_anchor_orbits_to_match() {
        // PIVOTED body at (1, 0) with pivot at origin; anchor at its centre. Locked body
        // at (0, 1). The pin-joint forces the rotation-free body's anchor to coincide with the
        // locked anchor: the body rotates around its pivot by π/2 so it lands at (0, 1).
        Body locked = Body.locked("locked", new Vec2(0.0, 1.0));
        Body rotFree = Body.pivoted("rf", new Vec2(1.0, 0.0), new Vec2(0.0, 0.0));
        PinJointConstraint c = new PinJointConstraint(
                AnchorPoint.atCentre(locked), AnchorPoint.atCentre(rotFree));

        SolverResult r = Solver.solve(SolverConfig.defaults(), List.of(c));
        assertTrue(r.converged(), () -> "expected convergence; got " + r);
        // Anchors coincide; rotation-free body is at (0, 1).
        assertEquals(0.0, rotFree.position().x(), EPS);
        assertEquals(1.0, rotFree.position().y(), EPS);
        // Pivot itself didn't move.
        assertEquals(Vec2.ZERO, rotFree.rotationPivot());
    }

    @Test
    void position_free_body_translates_to_satisfy_pin() {
        // ORIENTED body at (3, 2) — can translate but not rotate. Pinned to a locked anchor
        // at (0, 0): body slides to (0, 0).
        Body locked = Body.locked("l", new Vec2(0.0, 0.0));
        Body posFree = Body.oriented("p", new Vec2(3.0, 2.0));
        PinJointConstraint c = new PinJointConstraint(
                AnchorPoint.atCentre(locked), AnchorPoint.atCentre(posFree));

        c.project();
        assertEquals(0.0, posFree.position().x(), TIGHT);
        assertEquals(0.0, posFree.position().y(), TIGHT);
        // Angle untouched: zero rotational inverse inertia.
        assertEquals(0.0, posFree.angle(), TIGHT);
    }

    @Test
    void zero_residual_when_anchors_already_coincide() {
        Body a = Body.free("a", new Vec2(5.0, 5.0));
        Body b = Body.free("b", new Vec2(5.0, 5.0));
        PinJointConstraint c = new PinJointConstraint(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b));
        double residual = c.project();
        assertEquals(0.0, residual, TIGHT);
        // Bodies stayed put — the projection short-circuited before mutating either side.
        assertEquals(new Vec2(5.0, 5.0), a.position());
        assertEquals(new Vec2(5.0, 5.0), b.position());
    }
}
