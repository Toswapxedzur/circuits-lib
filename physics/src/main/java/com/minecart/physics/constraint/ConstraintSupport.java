package com.minecart.physics.constraint;

import com.minecart.physics.AnchorPoint;
import com.minecart.physics.Body;
import com.minecart.physics.Vec2;

/**
 * Shared numerical support for the rigid-body constraint projections. Collects the pieces that
 * were previously copy-pasted verbatim across the two-body distance-style constraints so a future
 * fix to the projection math (or a change to {@link #EPSILON}) happens in exactly one place.
 */
final class ConstraintSupport {

    /** Single canonical near-zero threshold for the constraint projections. */
    static final double EPSILON = 1e-12;

    private ConstraintSupport() {}

    /**
     * Standard two-body rigid PBD projection of a scalar constraint value {@code C} along a unit
     * {@code axis}. Builds the generalised inverse-mass scalar
     * {@code w_total = w_T_A + w_R_A·(r_A × axis)^2 + w_T_B + w_R_B·(r_B × axis)^2} from each
     * body's world-space lever arm (anchor minus rotation pivot), then applies the paired
     * translate / rotate correction {@code λ = -C / w_total} symmetrically to both bodies.
     *
     * <p>When {@code w_total == 0} (both endpoints fully locked along {@code axis}) no body is
     * mutated — the caller reports the residual and lets the solver decide on global progress.
     * The {@code axis} must be unit length; the caller owns that normalisation.
     */
    static void project(AnchorPoint anchorA, AnchorPoint anchorB, Vec2 axis, double C) {
        Vec2 wA = anchorA.worldPosition();
        Vec2 wB = anchorB.worldPosition();

        Body bodyA = anchorA.body();
        Body bodyB = anchorB.body();

        Vec2 rA = wA.sub(bodyA.rotationPivot());
        Vec2 rB = wB.sub(bodyB.rotationPivot());

        // 2D scalar cross product (r × axis).z — the lever arm for body rotation along the axis.
        double crossA = rA.cross(axis);
        double crossB = rB.cross(axis);

        double wTotal = bodyA.invMassT() + bodyA.invMassR() * crossA * crossA
                      + bodyB.invMassT() + bodyB.invMassR() * crossB * crossB;
        if (wTotal == 0.0) {
            return;
        }

        // λ = -C / w_total in the standard PBD derivation. Body.translate / Body.rotate apply the
        // inverse-inertia scaling internally, so we pass deltas WITHOUT pre-multiplying by w_T / w_R.
        double lambda = -C / wTotal;

        bodyA.translate(axis.scale(-lambda));
        bodyB.translate(axis.scale(lambda));
        bodyA.rotate(-lambda * crossA);
        bodyB.rotate(lambda * crossB);
    }
}
