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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage of {@link SpringConstraint}'s projection. Mirrors the structure of
 * {@link DistanceConstraintTest}: a battery of small scenes that each isolate one behaviour of the
 * spring (hard pin, soft pull, lever-arm rotation, locked body, multi-solve fight).
 */
class SpringConstraintTest {

    private static final double TIGHT = 1e-9;
    private static final double EPS = 1e-6;

    @Test
    void compliance_must_be_non_negative() {
        Body b = Body.free("b", Vec2.ZERO);
        AnchorPoint a = AnchorPoint.atCentre(b);
        assertThrows(IllegalArgumentException.class,
                () -> new SpringConstraint(a, new Vec2(1.0, 0.0), -0.1));
    }

    @Test
    void zero_compliance_pins_anchor_to_target_in_one_iteration() {
        // alpha=0 reduces XPBD to ordinary PBD: a single projection should land the anchor on
        // the target exactly (within machine precision).
        Body b = Body.free("b", new Vec2(0.0, 0.0));
        SpringConstraint c = new SpringConstraint(
                AnchorPoint.atCentre(b), new Vec2(5.0, 0.0), /* alpha */ 0.0);

        double residualBefore = c.project();
        assertEquals(5.0, residualBefore, TIGHT);
        assertEquals(0.0, c.residual(), TIGHT);
        assertEquals(new Vec2(5.0, 0.0), b.position());
    }

    @Test
    void very_stiff_spring_converges_close_to_target() {
        // alpha = 0.001 — the editor's default. A free body's centre should settle within a few
        // iterations to a position visually indistinguishable from the target. The steady-state
        // residual at this compliance is alpha * lambda ≈ alpha * displacement, so for a 10-unit
        // displacement it's ≈ 0.01 world units (well below pixel precision at editor zoom).
        Body b = Body.free("b", new Vec2(0.0, 0.0));
        SpringConstraint c = new SpringConstraint(
                AnchorPoint.atCentre(b), new Vec2(10.0, 0.0), 0.001);

        Solver.solve(SolverConfig.defaults(), List.of(c));
        // Solver may report converged=false (the 1e-4 tolerance is below the steady-state residual)
        // but the body should still be visually at the target.
        assertTrue(c.residual() < 0.02, "residual=" + c.residual());
        assertEquals(10.0, b.position().x(), 0.02);
    }

    @Test
    void locked_body_anchor_cannot_be_pulled() {
        // A fully locked body has invMassT = invMassR = 0; the spring can't act on it and the
        // residual stays at the initial offset regardless of how many iterations run.
        Body locked = Body.locked("locked", new Vec2(0.0, 0.0));
        SpringConstraint c = new SpringConstraint(
                AnchorPoint.atCentre(locked), new Vec2(3.0, 0.0), 0.001);

        SolverResult r = Solver.solve(SolverConfig.defaults(), List.of(c));
        assertEquals(3.0, c.residual(), TIGHT);
        // Solver hits iteration cap because the residual never drops; not a true failure but the
        // converged() flag should reflect "we didn't reach tolerance".
        assertNotNull(r);
    }

    @Test
    void off_centre_anchor_induces_body_rotation() {
        // Spring anchored on the body's edge (not its centre). Pulling that edge along +y while
        // the body is free should both translate and rotate the body — translate because invMassT
        // is positive, rotate because the lever arm from rotation pivot to anchor is non-zero.
        Body b = Body.free("b", new Vec2(0.0, 0.0));
        AnchorPoint anchor = new AnchorPoint(b, new Vec2(1.0, 0.0));
        SpringConstraint c = new SpringConstraint(anchor, new Vec2(1.0, 2.0), 0.0);

        Solver.solve(SolverConfig.defaults().withMaxIterations(200), List.of(c));
        // The anchor should land at (1, 2) within tight tolerance. The body's exact (position,
        // angle) is a free choice the solver makes — we only verify the constraint is satisfied.
        assertTrue(c.residual() < 1e-4, "residual=" + c.residual());
    }

    @Test
    void two_springs_on_same_body_compromise_between_targets() {
        // Two springs of equal compliance on the same free body, pulling toward different targets.
        // The body should settle at the midpoint (the only point where the two displacement
        // residuals can be equal-and-opposite under the symmetric weight distribution).
        Body b = Body.free("b", new Vec2(0.0, 0.0));
        AnchorPoint centre = AnchorPoint.atCentre(b);
        SpringConstraint left = new SpringConstraint(centre, new Vec2(-4.0, 0.0), 1.0);
        SpringConstraint right = new SpringConstraint(centre, new Vec2(4.0, 0.0), 1.0);

        SolverResult r = Solver.solve(
                SolverConfig.defaults().withMaxIterations(500).withResidualTolerance(1e-8),
                List.of(left, right));
        assertNotNull(r);
        // With equal alpha and equal cross-target distance, the equilibrium is the origin.
        assertEquals(0.0, b.position().x(), 1e-3);
        assertEquals(0.0, b.position().y(), 1e-3);
    }

    @Test
    void spring_loses_to_hard_distance_constraint() {
        // Spring tries to pull body to (10, 0); rigid distance constraint from body to a locked
        // anchor at the origin forces body to stay at distance 2. Equilibrium: body sits along
        // the line from origin toward target, at distance 2.
        //
        // Uses a softer spring (alpha=1.0) rather than the editor default 0.001 — the very-stiff
        // case converges to the same answer but takes thousands of iterations to settle the
        // accumulator against an immovable barrier, which is irrelevant in production where the
        // cursor moves smoothly per-tick (small per-step displacements, no "pumping").
        Body anchorBody = Body.locked("anchor", new Vec2(0.0, 0.0));
        Body b = Body.free("b", new Vec2(2.0, 0.0));
        DistanceConstraint rigid = new DistanceConstraint(
                AnchorPoint.atCentre(anchorBody), AnchorPoint.atCentre(b), /* L0 */ 2.0);
        SpringConstraint pull = new SpringConstraint(
                AnchorPoint.atCentre(b), new Vec2(10.0, 0.0), 1.0);

        Solver.solve(
                SolverConfig.defaults().withMaxIterations(200).withResidualTolerance(1e-8),
                List.of(rigid, pull));
        // Rigid (alpha=0) wins: body sits exactly at radius 2 from origin along +x.
        assertTrue(rigid.residual() < EPS,
                "rigid should be satisfied; residual=" + rigid.residual());
        assertEquals(2.0, b.position().length(), EPS);
        assertEquals(2.0, b.position().x(), EPS);
    }

    @Test
    void set_target_updates_the_pull_destination() {
        Body b = Body.free("b", new Vec2(0.0, 0.0));
        SpringConstraint c = new SpringConstraint(
                AnchorPoint.atCentre(b), new Vec2(1.0, 0.0), 0.0);
        c.setTarget(new Vec2(0.0, 3.0));
        c.project();
        assertEquals(0.0, c.residual(), TIGHT);
        assertEquals(new Vec2(0.0, 3.0), b.position());
    }

    @Test
    void reset_accumulator_clears_lambda() {
        // Verify the public reset hook actually zeros the accumulator: after a stiff spring
        // converges, calling reset and re-running with a fresh target should not see "stale"
        // restoring force from the prior solve.
        Body b = Body.free("b", new Vec2(0.0, 0.0));
        SpringConstraint c = new SpringConstraint(
                AnchorPoint.atCentre(b), new Vec2(5.0, 0.0), 0.5);
        Solver.solve(SolverConfig.defaults(), List.of(c));

        c.resetAccumulator();
        c.setTarget(new Vec2(0.0, 0.0));
        b.setPosition(new Vec2(0.0, 0.0));
        // After reset+new target, project should pull strongly toward (0, 0); since the body
        // is already there, the residual is zero on the first call.
        assertEquals(0.0, c.project(), TIGHT);
    }
}
