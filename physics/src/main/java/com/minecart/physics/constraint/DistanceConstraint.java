package com.minecart.physics.constraint;

import com.minecart.physics.AnchorPoint;
import com.minecart.physics.Vec2;

import java.util.Objects;

/**
 * Keeps two {@link AnchorPoint}s at a fixed Euclidean distance. Models a rigid bar / fixed-length
 * connector between two bodies — the canonical use case is a wire-as-rigid-bar where the two
 * endpoints sit on different rigid bodies and the wire's length must not change as the assembly
 * is dragged.
 *
 * <h2>Projection</h2>
 * Standard PBD distance projection, generalised to rigid bodies with rotation. The constraint
 * function is {@code C = |anchorB - anchorA| - L0}, satisfied when {@code C == 0}. Each
 * projection iteration computes the constraint gradient with respect to each body's
 * {@code (position, angle)}, builds the generalised inverse-mass scalar
 * {@code w_total = w_T_A + w_R_A * (r_A × n)^2 + w_T_B + w_R_B * (r_B × n)^2}
 * (where {@code n} is the unit vector along the constraint axis and {@code r_*} are the world-space
 * offsets from each body's rotation pivot to its anchor), then distributes the correction by
 * weighting against {@code w_total}.
 *
 * <h2>Degenerate inputs</h2>
 * Two cases are silently skipped:
 * <ul>
 *   <li>Coincident anchors at projection time (no axis to push along). The residual is reported as
 *       {@code |L0|} for the iteration so the solver can decide based on global progress.</li>
 *   <li>{@code w_total == 0}: both endpoint bodies are fully locked (no translation, no
 *       rotation). The constraint can't be satisfied; residual is reported as {@code |C|}.</li>
 * </ul>
 *
 * <h2>What this constraint does NOT do</h2>
 * <ul>
 *   <li>No compliance / softness. The PBD projection is hard — every iteration tries to drive
 *       {@code C} to zero. For soft / stretchy bars introduce XPBD compliance later; not in
 *       scope for the first solver.</li>
 *   <li>No inequality form. {@code |d| <= L0} (rope) or {@code |d| >= L0} (strut) are sibling
 *       constraint types, not modes of this one.</li>
 * </ul>
 */
public final class DistanceConstraint implements Constraint {

    private final AnchorPoint anchorA;
    private final AnchorPoint anchorB;
    private final double restLength;

    public DistanceConstraint(AnchorPoint anchorA, AnchorPoint anchorB, double restLength) {
        this.anchorA = Objects.requireNonNull(anchorA, "anchorA");
        this.anchorB = Objects.requireNonNull(anchorB, "anchorB");
        if (restLength < 0.0) {
            throw new IllegalArgumentException("restLength must be non-negative; got " + restLength);
        }
        this.restLength = restLength;
    }

    /**
     * Constructs a {@code DistanceConstraint} whose rest length equals the current distance
     * between {@code anchorA} and {@code anchorB} at construction time. Convenience for the
     * common case "make the current wire length the resting length".
     */
    public static DistanceConstraint atCurrentLength(AnchorPoint anchorA, AnchorPoint anchorB) {
        double dist = anchorB.worldPosition().sub(anchorA.worldPosition()).length();
        return new DistanceConstraint(anchorA, anchorB, dist);
    }

    public AnchorPoint anchorA() {
        return anchorA;
    }

    public AnchorPoint anchorB() {
        return anchorB;
    }

    public double restLength() {
        return restLength;
    }

    @Override
    public double project() {
        Vec2 wA = anchorA.worldPosition();
        Vec2 wB = anchorB.worldPosition();
        Vec2 d = wB.sub(wA);
        double dist = d.length();

        if (dist < ConstraintSupport.EPSILON) {
            // Anchors coincident — no constraint axis. The residual is whatever the rest length
            // asks for, but we can't push along an undefined direction in this iteration. A later
            // iteration will see the anchors separated (because some other constraint moved them)
            // and pick this back up.
            return restLength;
        }

        Vec2 n = d.scale(1.0 / dist);
        double C = dist - restLength;
        if (Math.abs(C) < ConstraintSupport.EPSILON) {
            return 0.0;
        }

        // Standard rigid-body distance projection along the unit axis n. The shared helper builds
        // w_total from each body's lever arm and applies the paired translate / rotate correction;
        // when both endpoints are fully locked (w_total == 0) it leaves the bodies untouched and we
        // report |C| so the solver decides on global progress.
        ConstraintSupport.project(anchorA, anchorB, n, C);

        return Math.abs(C);
    }

    @Override
    public double residual() {
        double dist = anchorB.worldPosition().sub(anchorA.worldPosition()).length();
        return Math.abs(dist - restLength);
    }

    @Override
    public String toString() {
        return "DistanceConstraint[" + anchorA + " <-> " + anchorB
                + " L0=" + restLength + "]";
    }
}
