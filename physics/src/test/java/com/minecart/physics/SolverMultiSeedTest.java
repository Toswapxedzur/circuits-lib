package com.minecart.physics;

import com.minecart.physics.constraint.Constraint;
import com.minecart.physics.constraint.DistanceConstraint;
import com.minecart.physics.constraint.SpringConstraint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage of the multi-seed solve scenarios the editor's per-tick drag aggregator produces:
 * several {@link SpringConstraint}s on different bodies in the same connected sub-graph, possibly
 * linked by rigid {@link DistanceConstraint}s and/or locked bodies. Verifies the solver behaves
 * sensibly when many simultaneous "pulls" share constraint mass through the network.
 *
 * <p>These tests intentionally use moderate compliance ({@code alpha ~ 1.0}) for the cases where
 * springs fight each other or fight a rigid linkage, because:
 * <ul>
 *   <li>The editor's default {@code 0.001} compliance is intended for the cursor-following case
 *       where each per-tick displacement is tiny — the springs never accumulate much before the
 *       target moves on. Pumping at near-hard stiffness against an immovable barrier is a
 *       multi-second-scale convergence problem outside any realistic per-tick budget.</li>
 *   <li>Moderate compliance lets us verify equilibrium <em>topology</em> (where the bodies settle
 *       relative to each other) in tens of iterations, which is what the test suite needs to be
 *       fast.</li>
 * </ul>
 */
class SolverMultiSeedTest {

    private static final double EPS = 1e-3;

    @Test
    void two_springs_on_different_bodies_each_reach_their_own_target() {
        // Two free bodies, NO coupling between them. Each gets its own spring. Should resolve
        // independently, each body landing at (or near) its target.
        Body a = Body.free("a", new Vec2(0.0, 0.0));
        Body b = Body.free("b", new Vec2(0.0, 0.0));

        SpringConstraint sa = new SpringConstraint(AnchorPoint.atCentre(a), new Vec2(5.0, 0.0), 0.001);
        SpringConstraint sb = new SpringConstraint(AnchorPoint.atCentre(b), new Vec2(0.0, 5.0), 0.001);

        Solver.solve(SolverConfig.defaults(), List.of(sa, sb));

        assertEquals(5.0, a.position().x(), 0.05);
        assertEquals(0.0, a.position().y(), 0.05);
        assertEquals(0.0, b.position().x(), 0.05);
        assertEquals(5.0, b.position().y(), 0.05);
    }

    @Test
    void two_springs_on_bodies_linked_by_rigid_edge_compromise() {
        // Two free bodies joined by a rigid bar of length 2. Each body has its own spring pulling
        // away from the other — the bar prevents them from each reaching their targets, so the
        // equilibrium is "bar fully aligned with the line between the two cursor targets, with
        // each body somewhere on that line".
        Body a = Body.free("a", new Vec2(-1.0, 0.0));
        Body b = Body.free("b", new Vec2(1.0, 0.0));

        DistanceConstraint bar = new DistanceConstraint(
                AnchorPoint.atCentre(a), AnchorPoint.atCentre(b), 2.0);
        // Springs pull A toward (-5, 0) and B toward (+5, 0). The bar's 2-unit length means they
        // can't both reach their targets; instead, they should settle symmetric about the origin
        // along the +x line.
        SpringConstraint pullA = new SpringConstraint(
                AnchorPoint.atCentre(a), new Vec2(-5.0, 0.0), 1.0);
        SpringConstraint pullB = new SpringConstraint(
                AnchorPoint.atCentre(b), new Vec2(5.0, 0.0), 1.0);

        Solver.solve(
                SolverConfig.defaults().withMaxIterations(500).withResidualTolerance(1e-8),
                List.of(bar, pullA, pullB));

        // Bar length preserved exactly.
        assertEquals(2.0, b.position().sub(a.position()).length(), EPS);
        // Symmetry: the midpoint of A and B sits on the x-axis (both pulls are symmetric in y).
        assertEquals(0.0, (a.position().y() + b.position().y()) / 2.0, EPS);
        // A is to the left of B (both pulls preserve ordering).
        assertTrue(a.position().x() < b.position().x(),
                "A should be left of B; a=" + a.position() + " b=" + b.position());
    }

    @Test
    void chain_anchored_one_end_dragged_other_end_extends_chain() {
        // Four-body chain along +x with the first body locked. A spring pulls the last body
        // farther out along +x toward a target. The chain should remain along +x with all bar
        // lengths preserved. Rotational PBD convergence for chains is a separate concern handled
        // by the topology suite — this test isolates "the rigid edges propagate the seed pull".
        Body locked = Body.locked("locked", new Vec2(0.0, 0.0));
        Body b = Body.free("b", new Vec2(1.0, 0.0));
        Body c = Body.free("c", new Vec2(2.0, 0.0));
        Body d = Body.free("d", new Vec2(3.0, 0.0));

        DistanceConstraint e1 = new DistanceConstraint(
                AnchorPoint.atCentre(locked), AnchorPoint.atCentre(b), 1.0);
        DistanceConstraint e2 = new DistanceConstraint(
                AnchorPoint.atCentre(b), AnchorPoint.atCentre(c), 1.0);
        DistanceConstraint e3 = new DistanceConstraint(
                AnchorPoint.atCentre(c), AnchorPoint.atCentre(d), 1.0);
        // Target sits beyond the chain's reach (chain length = 3, target at x=5) — D will be
        // pulled to the chain's maximum extension along +x.
        SpringConstraint pull = new SpringConstraint(
                AnchorPoint.atCentre(d), new Vec2(5.0, 0.0), 1.0);

        Solver.solve(
                SolverConfig.defaults().withMaxIterations(500).withResidualTolerance(1e-8),
                List.of(e1, e2, e3, pull));

        // All bar lengths preserved within tight tolerance.
        assertEquals(1.0, b.position().sub(locked.position()).length(), EPS);
        assertEquals(1.0, c.position().sub(b.position()).length(), EPS);
        assertEquals(1.0, d.position().sub(c.position()).length(), EPS);

        // The chain is at maximum extension along +x, so D sits at (3, 0).
        assertEquals(3.0, d.position().length(), 0.05);
        assertEquals(0.0, d.position().y(), EPS);
    }

    @Test
    void rotation_via_off_centre_spring_orients_body_toward_target() {
        // One free body, one spring whose anchor is NOT at the body's centre. Pulling the off-
        // centre anchor toward a target should both translate AND rotate the body so the anchor
        // ends up at the target. This is the "drag a port" scenario.
        Body body = Body.free("body", new Vec2(0.0, 0.0));
        AnchorPoint port = new AnchorPoint(body, new Vec2(1.0, 0.0));
        SpringConstraint pull = new SpringConstraint(port, new Vec2(0.0, 2.0), 0.0);

        Solver.solve(
                SolverConfig.defaults().withMaxIterations(200),
                List.of(pull));

        // The port (off-centre anchor) should be at the target. The body's centre is somewhere
        // along the way — we don't constrain that, just check the anchor satisfies the spring.
        assertEquals(0.0, pull.residual(), 1e-4);
    }

    @Test
    void contention_emulated_by_immobilising_the_contended_body() {
        // Test the policy the integration layer will apply: when two gestures target the same
        // body, that body is treated as LOCKED for the tick. Verify the locked body doesn't move
        // even with two springs pulling — and propagated motion still resolves via the rigid edge.
        //
        // Uses moderate spring compliance (alpha=1.0) rather than the editor default (0.001):
        // the locked body's spring lambda would otherwise pump indefinitely (no body translation
        // possible to balance C), and the neighbour spring fights the rigid edge in a way that
        // takes ~thousands of iterations to settle at near-stiffness. The behavioural assertion
        // (locked body unmoved, neighbour on unit circle, neighbour pulled toward +y) is
        // unchanged.
        Body locked = Body.locked("contended", new Vec2(0.0, 0.0));
        Body neighbour = Body.free("neighbour", new Vec2(1.0, 0.0));
        DistanceConstraint edge = new DistanceConstraint(
                AnchorPoint.atCentre(locked), AnchorPoint.atCentre(neighbour), 1.0);

        SpringConstraint pullA = new SpringConstraint(
                AnchorPoint.atCentre(locked), new Vec2(5.0, 0.0), 1.0);
        SpringConstraint pullB = new SpringConstraint(
                AnchorPoint.atCentre(locked), new Vec2(0.0, 5.0), 1.0);
        SpringConstraint pullNeighbour = new SpringConstraint(
                AnchorPoint.atCentre(neighbour), new Vec2(0.0, 5.0), 1.0);

        Solver.solve(
                SolverConfig.defaults().withMaxIterations(500).withResidualTolerance(1e-8),
                List.of(edge, pullA, pullB, pullNeighbour));

        // Locked body unmoved.
        assertEquals(new Vec2(0.0, 0.0), locked.position());
        // Neighbour stayed on the unit circle around the locked body.
        assertEquals(1.0, neighbour.position().length(), EPS);
        // Neighbour's spring pulled it toward (0, 5); it should be on the +y arc.
        assertTrue(neighbour.position().y() > 0.9,
                "neighbour should swing to +y; got " + neighbour.position());
    }
}
