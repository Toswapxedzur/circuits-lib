package com.minecart.physics;

import com.minecart.physics.constraint.Constraint;
import com.minecart.physics.constraint.DistanceConstraint;
import com.minecart.physics.constraint.PinJointConstraint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end {@link Solver} coverage against the topologies the editor's drag / rotate gestures
 * are expected to produce: short chains, anchored linkages, closed loops, and over-constrained
 * triangles. Each test sets up a known geometry, perturbs one body, and checks the solver brings
 * the assembly back to a constraint-satisfying pose (or, when it shouldn't be able to, leaves
 * the residual reported truthfully via {@link SolverResult#converged()}).
 */
class SolverTopologyTest {

    private static final double EPS = 1e-6;

    @Test
    void three_body_chain_with_distance_constraints_follows_the_dragged_end() {
        // Chain A - B - C linked by rigid bars (distance constraints). A is locked at (0, 0);
        // C is the "user-dragged" end (locked at its target so the solver treats it as a cursor
        // anchor, not a free body to pull back); B is free. The dragged target is intentionally
        // OFF the chain's fully-extended line — when the satisfying chain is fully extended,
        // the constraint Jacobians become collinear and PBD's convergence rate degenerates to
        // ~1.0 (no progress per iteration). With a folded target the axes stay well-separated
        // and convergence is fast.
        Body a = Body.locked("a", new Vec2(0.0, 0.0));
        Body b = Body.free("b", new Vec2(1.0, 0.0));
        Body c = Body.locked("c", new Vec2(1.0, 1.0));

        // Rest lengths capture the chain's natural unit-spaced layout.
        DistanceConstraint ab = new DistanceConstraint(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b), 1.0);
        DistanceConstraint bc = new DistanceConstraint(
                AnchorPoint.atCentre(b), AnchorPoint.atCentre(c), 1.0);

        SolverResult r = Solver.solve(SolverConfig.defaults(), List.of(ab, bc));
        assertTrue(r.converged(), () -> "expected convergence; got " + r);

        // Both bar lengths back to 1.
        double looseEps = 1e-3;
        assertEquals(1.0, distance(a, b), looseEps);
        assertEquals(1.0, distance(b, c), looseEps);
        // Locked ends stayed put.
        assertEquals(new Vec2(0.0, 0.0), a.position());
        assertEquals(new Vec2(1.0, 1.0), c.position());
    }

    @Test
    void four_bar_loop_closes_when_one_corner_is_perturbed_slightly() {
        // Four-body loop A - B - C - D - A linked by rigid bars. Initial geometry: unit square.
        //   A (0,0)  -  B (1,0)
        //     |             |
        //   D (0,1)  -  C (1,1)
        // A locked. Slightly perturb B's position; the solver should restore the square within
        // floating-point tolerance.
        Body a = Body.locked("a", new Vec2(0.0, 0.0));
        Body b = Body.free("b", new Vec2(1.0, 0.0));
        Body c = Body.free("c", new Vec2(1.0, 1.0));
        Body d = Body.free("d", new Vec2(0.0, 1.0));

        DistanceConstraint ab = new DistanceConstraint(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b), 1.0);
        DistanceConstraint bc = new DistanceConstraint(
                AnchorPoint.atCentre(b), AnchorPoint.atCentre(c), 1.0);
        DistanceConstraint cd = new DistanceConstraint(
                AnchorPoint.atCentre(c), AnchorPoint.atCentre(d), 1.0);
        DistanceConstraint da = new DistanceConstraint(
                AnchorPoint.atCentre(d), AnchorPoint.atCentre(a), 1.0);

        // Tiny perturbation of B — solver should pull everything back to a valid closed loop.
        // Closed 4-bar with unit edges has an internal scissor DOF (parallelogram fold), so the
        // resulting shape isn't necessarily the original square — but every edge length must
        // come back to 1.
        b.setPosition(new Vec2(1.05, 0.02));

        SolverConfig moreIterations = SolverConfig.defaults().withMaxIterations(200);
        SolverResult r = Solver.solve(moreIterations, List.of(ab, bc, cd, da));
        assertTrue(r.converged(),
                () -> "expected convergence; got " + r);

        // All four bar lengths back to 1. Looser tolerance than the EPS used for two-body cases
        // because PBD on a closed loop converges geometrically rather than to machine precision.
        double looseEps = 1e-3;
        assertEquals(1.0, distance(a, b), looseEps);
        assertEquals(1.0, distance(b, c), looseEps);
        assertEquals(1.0, distance(c, d), looseEps);
        assertEquals(1.0, distance(d, a), looseEps);
    }

    @Test
    void anchored_linkage_drag_settles_with_residual_when_target_is_unreachable() {
        // 2-bar chain A - B - C with both ends locked. Bar lengths 1, 1, so the only B that
        // satisfies both constraints must lie on two unit circles: one around A (0, 0) and one
        // around C — and the locked C target is placed at (5, 0), far outside the chain's
        // 2-unit-radius reachable workspace. PBD leaves residual; the solver should report
        // non-convergence rather than claiming false success.
        Body a = Body.locked("a", new Vec2(0.0, 0.0));
        Body b = Body.free("b", new Vec2(1.0, 0.0));
        // Lock C at the unreachable drag target.
        Body c = Body.locked("c", new Vec2(5.0, 0.0));

        DistanceConstraint ab = new DistanceConstraint(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b), 1.0);
        DistanceConstraint bc = new DistanceConstraint(
                AnchorPoint.atCentre(b), AnchorPoint.atCentre(c), 1.0);

        SolverResult r = Solver.solve(SolverConfig.defaults(), List.of(ab, bc));
        assertFalse(r.converged(),
                () -> "expected non-convergence (unreachable target); got " + r);
        assertTrue(r.residual() > 1.0,
                () -> "expected substantial residual; got " + r.residual());
        // Locked endpoints unchanged.
        assertEquals(new Vec2(0.0, 0.0), a.position());
        assertEquals(new Vec2(5.0, 0.0), c.position());
    }

    @Test
    void pin_joint_chain_propagates_correction_through_multiple_hops() {
        // Three-body chain joined by pin joints (shared nodes) rather than distance constraints.
        // A locked at (0,0). Pin A=B, pin B=C. Initially A=(0,0), B=(5,5), C=(10,-3).
        // Solver should pull B and C to coincide with A.
        Body a = Body.locked("a", new Vec2(0.0, 0.0));
        Body b = Body.free("b", new Vec2(5.0, 5.0));
        Body c = Body.free("c", new Vec2(10.0, -3.0));

        PinJointConstraint ab = new PinJointConstraint(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b));
        PinJointConstraint bc = new PinJointConstraint(
                AnchorPoint.atCentre(b), AnchorPoint.atCentre(c));

        SolverResult r = Solver.solve(SolverConfig.defaults(), List.of(ab, bc));
        assertTrue(r.converged(), () -> "expected convergence; got " + r);

        // All three bodies coincide at the locked anchor's pose (0, 0). The tolerance is matched
        // to the solver's residual tolerance (1e-4) rather than the {@link #EPS} used for closed-
        // form two-body tests, because a chain converges asymptotically rather than exactly.
        double chainEps = 1e-3;
        assertEquals(0.0, b.position().x(), chainEps);
        assertEquals(0.0, b.position().y(), chainEps);
        assertEquals(0.0, c.position().x(), chainEps);
        assertEquals(0.0, c.position().y(), chainEps);
    }

    @Test
    void mixed_distance_and_pin_constraints_in_a_branching_topology() {
        // Branching topology: A (locked) - distance(2) - B - pin - C, plus B - distance(2) - D.
        // Lock D at a non-collinear cursor target so the solver treats it as an immovable anchor
        // and the AB and BD constraints stay non-degenerate at the satisfying point.
        Body a = Body.locked("a", new Vec2(0.0, 0.0));
        Body b = Body.free("b", new Vec2(2.0, 0.0));
        Body c = Body.free("c", new Vec2(2.0, 0.0));    // initially pinned at B
        // Lock D at the cursor target. (2, 2) is a non-degenerate location: dist(A, D) = √8,
        // so the satisfying B is the apex of an isoceles triangle on AD with legs of length 2.
        Body d = Body.locked("d", new Vec2(2.0, 2.0));

        DistanceConstraint ab = new DistanceConstraint(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b), 2.0);
        PinJointConstraint bc = new PinJointConstraint(
                AnchorPoint.atCentre(b), AnchorPoint.atCentre(c));
        DistanceConstraint bd = new DistanceConstraint(
                AnchorPoint.atCentre(b), AnchorPoint.atCentre(d), 2.0);

        SolverResult r = Solver.solve(SolverConfig.defaults(), List.of(ab, bc, bd));
        assertTrue(r.converged(), () -> "expected convergence; got " + r);

        double looseEps = 1e-3;
        assertEquals(2.0, distance(a, b), looseEps);
        assertEquals(2.0, distance(b, d), looseEps);
        // B and C still pinned.
        assertEquals(0.0, b.position().sub(c.position()).length(), looseEps);
    }

    @Test
    void empty_constraint_list_returns_immediately_converged() {
        SolverResult r = Solver.solve(SolverConfig.defaults(), List.of());
        assertEquals(0, r.iterations());
        assertEquals(0.0, r.residual());
        assertTrue(r.converged());
    }

    @Test
    void deterministic_ordering_yields_repeatable_results() {
        // Two identical scenarios run independently; final poses must be bit-identical because
        // the solver is deterministic given a fixed constraint order.
        Body a1 = Body.free("a", new Vec2(0.0, 0.0));
        Body b1 = Body.free("b", new Vec2(3.0, 0.0));
        Body c1 = Body.free("c", new Vec2(6.0, 0.0));

        Body a2 = Body.free("a", new Vec2(0.0, 0.0));
        Body b2 = Body.free("b", new Vec2(3.0, 0.0));
        Body c2 = Body.free("c", new Vec2(6.0, 0.0));

        List<Constraint> scenario1 = List.of(
                new DistanceConstraint(AnchorPoint.atCentre(a1), AnchorPoint.atCentre(b1), 2.0),
                new DistanceConstraint(AnchorPoint.atCentre(b1), AnchorPoint.atCentre(c1), 2.0));
        List<Constraint> scenario2 = List.of(
                new DistanceConstraint(AnchorPoint.atCentre(a2), AnchorPoint.atCentre(b2), 2.0),
                new DistanceConstraint(AnchorPoint.atCentre(b2), AnchorPoint.atCentre(c2), 2.0));

        Solver.solve(SolverConfig.defaults(), scenario1);
        Solver.solve(SolverConfig.defaults(), scenario2);

        assertEquals(a1.position(), a2.position());
        assertEquals(b1.position(), b2.position());
        assertEquals(c1.position(), c2.position());
    }

    @Test
    void large_loop_propagates_disturbance_around_the_ring() {
        // 6-body pin-joint ring with one locked anchor. All bodies start at the origin so the
        // pin constraints are trivially satisfied; we then perturb the body opposite the anchor
        // and watch the ring pull itself back. Corrections propagate one hop per Gauss-Seidel
        // pass around the ring, so the diameter (n/2) dictates the minimum useful iteration
        // count; convergence beyond that is geometric and asymptotic. The 6-body ring's
        // diameter of 3 fits well within a bumped-iteration budget for from-scratch test runs.
        int n = 6;
        List<Body> bodies = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            bodies.add(i == 0 ? Body.locked("b" + i, Vec2.ZERO) : Body.free("b" + i, Vec2.ZERO));
        }
        List<Constraint> constraints = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Body left = bodies.get(i);
            Body right = bodies.get((i + 1) % n);
            constraints.add(new PinJointConstraint(
                    AnchorPoint.atCentre(left), AnchorPoint.atCentre(right)));
        }

        // Disturb the body opposite the anchor.
        bodies.get(n / 2).setPosition(new Vec2(1.0, 0.7));

        SolverConfig conf = SolverConfig.defaults().withMaxIterations(500);
        SolverResult r = Solver.solve(conf, constraints);
        assertTrue(r.converged(), () -> "expected convergence; got " + r);
        // Every body should have returned to the origin (the locked anchor's pose) within
        // tolerance.
        double looseEps = 1e-3;
        for (int i = 0; i < n; i++) {
            assertEquals(0.0, bodies.get(i).position().length(), looseEps,
                    "body " + i + " did not return to origin");
        }
    }

    private static double distance(Body a, Body b) {
        return b.position().sub(a.position()).length();
    }
}
