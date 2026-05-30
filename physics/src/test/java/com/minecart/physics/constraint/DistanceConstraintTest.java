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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage of {@link DistanceConstraint}'s projection. Each test sets up a known geometry, runs
 * one (or a small number of) projection iterations, and checks the residual collapses as
 * expected — or stays put when the constraint is unable to act.
 */
class DistanceConstraintTest {

    /**
     * The DistanceConstraint and PinJointConstraint solve translation-only configurations in
     * exactly one iteration (the closed-form Lagrange-multiplier formula is exact for two point
     * masses). Tests that involve rigid-body rotation, however, are PBD's linearised step and
     * converge over many iterations; we drive those to convergence via the full solver.
     */
    private static final double TIGHT = 1e-9;
    private static final double EPS = 1e-6;

    @Test
    void rest_length_must_be_non_negative() {
        Body a = Body.free("a", Vec2.ZERO);
        Body b = Body.free("b", Vec2.ZERO);
        AnchorPoint ap = AnchorPoint.atCentre(a);
        AnchorPoint bp = AnchorPoint.atCentre(b);
        assertThrows(IllegalArgumentException.class,
                () -> new DistanceConstraint(ap, bp, -1.0));
    }

    @Test
    void single_iteration_satisfies_two_free_point_bodies() {
        // Two free bodies sitting too far apart; one PBD iteration should bring them to L0.
        Body a = Body.free("a", new Vec2(0.0, 0.0));
        Body b = Body.free("b", new Vec2(4.0, 0.0));
        DistanceConstraint c = new DistanceConstraint(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b), /* L0 */ 2.0);

        double residualBefore = c.project();
        // project() returns the residual BEFORE the correction (so we can detect convergence
        // without an extra read), so an initial gap of 4 - 2 = 2 reports residual = 2.
        assertEquals(2.0, residualBefore, TIGHT);

        // After the projection, the constraint is satisfied to machine precision.
        assertEquals(0.0, c.residual(), TIGHT);
        // The correction was split equally because both bodies have unit inverse inertia: each
        // moved 1 unit toward the other along the line.
        assertEquals(1.0, a.position().x(), TIGHT);
        assertEquals(3.0, b.position().x(), TIGHT);
    }

    @Test
    void one_locked_endpoint_absorbs_all_correction_on_the_free_side() {
        // A locked, B free. Pulling them to distance 2: A should not move; B should move from
        // (4, 0) to (2, 0).
        Body a = Body.locked("a", new Vec2(0.0, 0.0));
        Body b = Body.free("b", new Vec2(4.0, 0.0));
        DistanceConstraint c = new DistanceConstraint(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b), 2.0);

        c.project();
        assertEquals(Vec2.ZERO, a.position());
        assertEquals(2.0, b.position().x(), TIGHT);
    }

    @Test
    void both_endpoints_locked_leaves_residual_unchanged() {
        Body a = Body.locked("a", new Vec2(0.0, 0.0));
        Body b = Body.locked("b", new Vec2(4.0, 0.0));
        DistanceConstraint c = new DistanceConstraint(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b), 2.0);

        double residualBefore = c.project();
        // project() reported the pre-projection residual; the bodies didn't move; the residual
        // afterwards is still the same.
        assertEquals(2.0, residualBefore, TIGHT);
        assertEquals(2.0, c.residual(), TIGHT);
        assertEquals(Vec2.ZERO, a.position());
        assertEquals(new Vec2(4.0, 0.0), b.position());
    }

    @Test
    void already_satisfied_returns_zero_residual_with_no_motion() {
        Body a = Body.free("a", Vec2.ZERO);
        Body b = Body.free("b", new Vec2(3.0, 0.0));
        DistanceConstraint c = new DistanceConstraint(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b), 3.0);

        double residual = c.project();
        assertEquals(0.0, residual, TIGHT);
        // No bodies moved.
        assertEquals(Vec2.ZERO, a.position());
        assertEquals(new Vec2(3.0, 0.0), b.position());
    }

    @Test
    void atCurrentLength_factory_pins_existing_separation() {
        Body a = Body.free("a", new Vec2(1.0, 1.0));
        Body b = Body.free("b", new Vec2(4.0, 5.0));
        DistanceConstraint c = DistanceConstraint.atCurrentLength(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b));

        // L0 = current distance = 5 (3-4-5 triangle).
        assertEquals(5.0, c.restLength(), TIGHT);
        assertEquals(0.0, c.residual(), TIGHT);
    }

    @Test
    void rigid_body_with_offset_anchor_uses_rotation_to_close_residual() {
        // Free body at origin with anchor offset (1, 0) → world anchor at (1, 0).
        // Locked partner at (0, 1.5). Rest length 1. The body must rotate so its anchor lands at
        // distance 1 from (0, 1.5). PBD's linearised step takes multiple iterations; we use the
        // solver to drive it to convergence.
        Body a = Body.free("a", new Vec2(0.0, 0.0));
        AnchorPoint anchorA = new AnchorPoint(a, new Vec2(1.0, 0.0));
        Body b = Body.locked("b", new Vec2(0.0, 1.5));
        AnchorPoint anchorB = AnchorPoint.atCentre(b);

        DistanceConstraint c = new DistanceConstraint(anchorA, anchorB, 1.0);

        SolverResult r = Solver.solve(SolverConfig.defaults(), List.of(c));
        assertTrue(r.converged(),
                () -> "expected convergence; got " + r);
        // The constraint should now hold: anchorA is exactly 1 unit from anchorB.
        double finalDist = anchorB.worldPosition().sub(anchorA.worldPosition()).length();
        assertEquals(1.0, finalDist, EPS);
    }

    @Test
    void coincident_anchors_with_nonzero_rest_length_skip_iteration_but_keep_residual() {
        Body a = Body.free("a", Vec2.ZERO);
        Body b = Body.free("b", Vec2.ZERO);
        DistanceConstraint c = new DistanceConstraint(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b), 2.0);

        double residual = c.project();
        // Degenerate axis — no projection possible. The constraint reports its rest length as
        // residual (callers can decide to nudge the bodies apart externally before retrying).
        assertEquals(2.0, residual, TIGHT);
        // No motion: the projection bailed before any body mutation.
        assertEquals(Vec2.ZERO, a.position());
        assertEquals(Vec2.ZERO, b.position());
    }

    @Test
    void position_free_endpoint_translates_but_doesnt_rotate() {
        // An ORIENTED body should translate freely along the constraint axis. Initial set-up:
        // ORIENTED body at (3, 0); locked body at (0, 0); L0 = 1. The ORIENTED body
        // must slide from (3,0) toward (1,0) (rest distance from the locked end).
        Body a = Body.locked("a", Vec2.ZERO);
        Body b = Body.oriented("b", new Vec2(3.0, 0.0));
        DistanceConstraint c = new DistanceConstraint(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b), 1.0);

        c.project();
        // b translated to (1, 0).
        assertEquals(1.0, b.position().x(), TIGHT);
        assertEquals(0.0, b.position().y(), TIGHT);
        // and didn't rotate.
        assertEquals(0.0, b.angle(), TIGHT);
    }

    @Test
    void converged_flag_false_when_iteration_cap_is_too_low() {
        // Pathologically tight tolerance + 1 iteration on a non-trivial geometry: PBD's
        // linearised step doesn't reach it in one pass, so the solver reports
        // converged=false. This pins the behaviour that the iteration cap is honoured even
        // when residual is still large.
        Body a = Body.free("a", Vec2.ZERO);
        AnchorPoint anchorA = new AnchorPoint(a, new Vec2(1.0, 0.0));
        Body b = Body.locked("b", new Vec2(0.0, 1.5));
        AnchorPoint anchorB = AnchorPoint.atCentre(b);
        DistanceConstraint c = new DistanceConstraint(anchorA, anchorB, 1.0);

        SolverConfig tight = new SolverConfig(1, 1e-12);
        SolverResult r = Solver.solve(tight, List.of(c));
        assertFalse(r.converged());
        assertEquals(1, r.iterations());
    }
}
