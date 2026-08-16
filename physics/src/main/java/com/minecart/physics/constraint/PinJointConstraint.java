package com.minecart.physics.constraint;

import com.minecart.physics.AnchorPoint;
import com.minecart.physics.Vec2;

import java.util.Objects;

/**
 * Forces two {@link AnchorPoint}s to coincide in world space. Models a shared node between two
 * bodies — equivalent to a 2D revolute (pin) joint that allows both bodies to rotate freely
 * around the shared point while keeping their attached anchor points in agreement on its
 * location.
 *
 * <h2>Projection</h2>
 * Implemented as an iterative single-axis distance projection with rest length zero: each call
 * picks the current residual vector {@code C = wB - wA} as the constraint axis and applies a
 * standard PBD distance-constraint correction along it. Because the axis is recomputed each
 * iteration, the formulation converges across multiple Gauss-Seidel passes even when the bodies'
 * effective inverse-inertia along any particular direction is degenerate (e.g. a rotation-only
 * body pivoted such that the residual has a component perpendicular to the body's reachable
 * circle).
 *
 * <h2>Why iterative single-axis rather than a closed-form 2D K matrix</h2>
 * The closed-form 2D Lagrange-multiplier projection (with K = ∂C/∂q M⁻¹ (∂C/∂q)ᵀ) converges in
 * one step for free / point-mass endpoints, but fails on bodies whose only available DOF doesn't
 * span the constraint's 2D residual — K then has rank 1 (or 0) and isn't invertible. The
 * iterative form gracefully sidesteps this: by projecting along the residual axis each pass, it
 * always makes progress along the direction the system actually cares about, and over-shoots
 * from large rotation deltas are corrected on subsequent iterations.
 *
 * <h2>Degenerate inputs</h2>
 * Two cases short-circuit cleanly:
 * <ul>
 *   <li>{@code |C| < EPSILON}: already satisfied; reports residual {@code 0} without mutating
 *       either body.</li>
 *   <li>{@code w_total == 0} (both bodies fully locked along the current axis): reports the
 *       current residual, no body motion.</li>
 * </ul>
 */
public final class PinJointConstraint implements Constraint {

    private final AnchorPoint anchorA;
    private final AnchorPoint anchorB;

    public PinJointConstraint(AnchorPoint anchorA, AnchorPoint anchorB) {
        this.anchorA = Objects.requireNonNull(anchorA, "anchorA");
        this.anchorB = Objects.requireNonNull(anchorB, "anchorB");
    }

    public AnchorPoint anchorA() {
        return anchorA;
    }

    public AnchorPoint anchorB() {
        return anchorB;
    }

    @Override
    public double project() {
        Vec2 wA = anchorA.worldPosition();
        Vec2 wB = anchorB.worldPosition();
        Vec2 d = wB.sub(wA);
        double dist = d.length();
        if (dist < ConstraintSupport.EPSILON) {
            return 0.0;
        }

        Vec2 n = d.scale(1.0 / dist);
        // For a pin joint, C = |d| (we want |d| → 0). This is exactly a distance constraint with
        // rest length 0, so we delegate to the same shared projection arithmetic the distance
        // constraint uses — a future fix to the rotation-aware Jacobian then happens in one place.
        ConstraintSupport.project(anchorA, anchorB, n, dist);

        return dist;
    }

    @Override
    public double residual() {
        return anchorB.worldPosition().sub(anchorA.worldPosition()).length();
    }

    @Override
    public String toString() {
        return "PinJointConstraint[" + anchorA + " <-> " + anchorB + "]";
    }
}
