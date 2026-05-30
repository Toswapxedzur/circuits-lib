package com.minecart.physics;

import com.minecart.physics.constraint.Constraint;
import com.minecart.physics.constraint.DistanceConstraint;
import com.minecart.physics.constraint.PinJointConstraint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustively walks the lock-state matrix for a single 2-body constraint and verifies the solver
 * obeys per-axis inverse-inertia gating. The matrix mirrors the domain layer's {@code LockMode}
 * enum: {@code FREE / ORIENTED / PIVOTED / LOCKED} on each side, all 16 combinations.
 *
 * <p>Each case sets up a known initial offset and a single distance or pin constraint and asks
 * the solver to converge. The expected outcome is computed from the available DOFs:
 * <ul>
 *   <li>Both fully locked → never converges (no DOF to absorb correction).</li>
 *   <li>One side fully locked → the other side must satisfy on its own.</li>
 *   <li>Otherwise → both sides share the correction, weighted by inverse inertia.</li>
 * </ul>
 */
class SolverLockTest {

    private static final double EPS = 1e-6;

    private enum Mode {
        FREE,
        ORIENTED,
        PIVOTED,
        LOCKED
    }

    private static Body bodyFor(Mode mode, Object id, Vec2 pos, Vec2 pivot) {
        return switch (mode) {
            case FREE -> Body.free(id, pos);
            case ORIENTED -> Body.oriented(id, pos);
            case PIVOTED -> Body.pivoted(id, pos, pivot);
            case LOCKED -> Body.locked(id, pos);
        };
    }

    /**
     * For a 2-body distance / pin constraint, the constraint is satisfiable iff at least one
     * body carries an inverse inertia along the constraint axis (translation) — the only DOF
     * relevant for point-mass anchor bodies. With anchors-at-centre and pivots-at-position,
     * PIVOTED bodies contribute nothing to translation, so they look "locked" from the
     * constraint's perspective.
     */
    private static boolean expectsConvergence(Mode a, Mode b) {
        boolean aTranslates = a == Mode.FREE || a == Mode.ORIENTED;
        boolean bTranslates = b == Mode.FREE || b == Mode.ORIENTED;
        return aTranslates || bTranslates;
    }

    @Test
    void distance_constraint_two_body_lock_matrix() {
        for (Mode aMode : Mode.values()) {
            for (Mode bMode : Mode.values()) {
                runDistanceCase(aMode, bMode);
            }
        }
    }

    @Test
    void pin_joint_two_body_lock_matrix() {
        for (Mode aMode : Mode.values()) {
            for (Mode bMode : Mode.values()) {
                runPinCase(aMode, bMode);
            }
        }
    }

    private static void runDistanceCase(Mode aMode, Mode bMode) {
        // Initial separation = 4, rest length = 2. Both pivots = each body's position (so
        // pivoted bodies orbit around their starting point, contributing zero translation
        // and effectively behaving as locked for centre-anchored constraints).
        Vec2 posA = new Vec2(0.0, 0.0);
        Vec2 posB = new Vec2(4.0, 0.0);
        Body a = bodyFor(aMode, "a-" + aMode + "-" + bMode, posA, posA);
        Body b = bodyFor(bMode, "b-" + aMode + "-" + bMode, posB, posB);
        DistanceConstraint c = new DistanceConstraint(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b), 2.0);

        SolverResult result = Solver.solve(SolverConfig.defaults(), List.of(c));

        if (expectsConvergence(aMode, bMode)) {
            assertTrue(result.converged(),
                    () -> "expected convergence for " + aMode + " - " + bMode + "; got " + result);
            assertEquals(2.0, b.position().sub(a.position()).length(), EPS,
                    "wrong final distance for " + aMode + " - " + bMode);
        } else {
            assertFalse(result.converged(),
                    () -> "expected non-convergence for " + aMode + " - " + bMode + "; got " + result);
            // Bodies stay put when neither side can translate.
            assertEquals(posA, a.position(), () -> "A moved for " + aMode + "-" + bMode);
            assertEquals(posB, b.position(), () -> "B moved for " + aMode + "-" + bMode);
        }
    }

    private static void runPinCase(Mode aMode, Mode bMode) {
        Vec2 posA = new Vec2(0.0, 0.0);
        Vec2 posB = new Vec2(3.0, 4.0);
        Body a = bodyFor(aMode, "a-pin-" + aMode + "-" + bMode, posA, posA);
        Body b = bodyFor(bMode, "b-pin-" + aMode + "-" + bMode, posB, posB);
        PinJointConstraint c = new PinJointConstraint(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b));

        SolverResult result = Solver.solve(SolverConfig.defaults(), List.of(c));

        if (expectsConvergence(aMode, bMode)) {
            assertTrue(result.converged(),
                    () -> "expected pin convergence for " + aMode + " - " + bMode + "; got " + result);
            // The two bodies end at the same world point.
            assertEquals(0.0, b.position().sub(a.position()).length(), EPS,
                    "anchors didn't coincide for " + aMode + " - " + bMode);
        } else {
            assertFalse(result.converged(),
                    () -> "expected pin non-convergence for " + aMode + " - " + bMode + "; got " + result);
            assertEquals(posA, a.position());
            assertEquals(posB, b.position());
        }
    }

    @Test
    void oriented_partner_absorbs_translation_when_other_side_is_locked() {
        // ORIENTED body opposite a LOCKED body. Translation is the only feasible motion;
        // the ORIENTED body translates to satisfy the constraint, the locked body stays
        // put. Angles unchanged on both sides.
        Body locked = Body.locked("l", new Vec2(0.0, 0.0));
        Body posFree = Body.oriented("p", new Vec2(5.0, 0.0));
        DistanceConstraint c = new DistanceConstraint(
                AnchorPoint.atCentre(locked), AnchorPoint.atCentre(posFree), 1.0);

        SolverResult r = Solver.solve(SolverConfig.defaults(), List.of(c));
        assertTrue(r.converged(), () -> "expected convergence; got " + r);
        assertEquals(new Vec2(0.0, 0.0), locked.position());
        // ORIENTED body should land 1 unit from origin along +x.
        assertEquals(1.0, posFree.position().x(), EPS);
        assertEquals(0.0, posFree.position().y(), EPS);
        assertEquals(0.0, posFree.angle(), EPS);
    }

    @Test
    void pivoted_with_offset_anchor_can_satisfy_via_rotation_alone() {
        // Setup: PIVOTED body pivoted at (0, 0) with translational position (1, 0) and
        // anchor at body centre. The body's "anchor world position" is just its position (=
        // anchor-at-centre). For PIVOTED the only DOF is rotation around the pivot.
        // The constraint forces the body's anchor to be at distance 1 from a LOCKED anchor at
        // (0, 1). The PIVOTED body's anchor moves on a circle of radius 1 around (0, 0),
        // so the satisfying poses are the intersections of two circles: the body's circle and
        // the constraint circle of radius 1 around (0, 1).
        //   Intersections: (sin θ, 1 - cos θ) = (cos α, sin α) ... or numerically the unique pose
        //   at θ = π/2 (body anchor at (0, 1)) is a valid solution where distance is 0 — but we
        //   want distance 1, so the body must rotate to a pose at distance 1 from (0, 1).
        // Easier setup: locked anchor at (0, 0), and the constraint is "anchorB at distance 0
        // from locked" — degenerate. Let me pick a clearer geometry:
        //
        // Rotation-free body pivoted at origin, position (2, 0), anchor at centre. Locked anchor
        // at (0, 2). Distance constraint with rest length |B - A| = 2*sqrt(2)... or we just use a
        // pin and demand they coincide. Pin's resolution: rotate the body by π/2 so its anchor
        // moves from (2, 0) to (0, 2). Body angle goes from 0 to π/2.
        Body locked = Body.locked("anchor", new Vec2(0.0, 2.0));
        Body rotFree = Body.pivoted("rf", new Vec2(2.0, 0.0), new Vec2(0.0, 0.0));
        PinJointConstraint c = new PinJointConstraint(
                AnchorPoint.atCentre(locked), AnchorPoint.atCentre(rotFree));

        SolverResult r = Solver.solve(SolverConfig.defaults(), List.of(c));
        assertTrue(r.converged(), () -> "expected convergence; got " + r);
        // Body's position rotated 90° CCW around the pivot: (2, 0) → (0, 2).
        assertEquals(0.0, rotFree.position().x(), EPS);
        assertEquals(2.0, rotFree.position().y(), EPS);
        // Pivot itself never moves.
        assertEquals(new Vec2(0.0, 0.0), rotFree.rotationPivot());
        assertEquals(Math.PI / 2.0, rotFree.angle(), EPS);
    }

    @Test
    void weighted_inertias_distribute_correction_proportionally() {
        // A body with invMassT = 1, B body with invMassT = 0.25 (4× heavier). Each pulled toward
        // the other under a distance constraint with rest length 0 (pure pin), expect the
        // displacement to split 4:1 in favour of the lighter (A) body.
        Body a = new Body("a", new Vec2(0.0, 0.0), 0.0, new Vec2(0.0, 0.0), 1.0, 0.0);
        Body b = new Body("b", new Vec2(5.0, 0.0), 0.0, new Vec2(5.0, 0.0), 0.25, 0.0);
        PinJointConstraint c = new PinJointConstraint(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b));

        Solver.solve(SolverConfig.defaults(), List.of(c));

        // Weighted-mass split: A gets 1/(1+0.25) = 0.8 of the correction, B gets 0.25/(1+0.25) = 0.2.
        // So A ends at 0.8 * 5 = 4 and B ends at 5 - 0.2 * 5 = 4. Both at x=4.
        assertEquals(4.0, a.position().x(), EPS);
        assertEquals(4.0, b.position().x(), EPS);
    }
}
