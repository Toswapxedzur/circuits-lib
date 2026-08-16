package com.minecart.physics.constraint;

import com.minecart.physics.AnchorPoint;
import com.minecart.physics.Vec2;

import java.util.Objects;

/**
 * Keeps two anchors parallel to an authored direction while allowing length changes. This is the
 * edge-lock counterpart of a rigid distance constraint: it constrains orientation only, not length.
 */
public final class EdgeOrientationConstraint implements Constraint {

    private final AnchorPoint anchorA;
    private final AnchorPoint anchorB;
    private final Vec2 normal;

    public EdgeOrientationConstraint(AnchorPoint anchorA, AnchorPoint anchorB, Vec2 direction) {
        this.anchorA = Objects.requireNonNull(anchorA, "anchorA");
        this.anchorB = Objects.requireNonNull(anchorB, "anchorB");
        Objects.requireNonNull(direction, "direction");
        if (direction.lengthSquared() < ConstraintSupport.EPSILON) {
            throw new IllegalArgumentException("direction must be non-zero");
        }
        Vec2 unit = direction.normalised();
        this.normal = unit.perpendicular();
    }

    @Override
    public double project() {
        Vec2 wA = anchorA.worldPosition();
        Vec2 wB = anchorB.worldPosition();
        double C = wB.sub(wA).dot(normal);
        if (Math.abs(C) < ConstraintSupport.EPSILON) {
            return 0.0;
        }

        // The constraint axis is the authored normal (already unit length). The shared projection
        // builds w_total from each body's lever arm and applies the paired correction, leaving the
        // bodies untouched when both are fully locked (w_total == 0).
        ConstraintSupport.project(anchorA, anchorB, normal, C);
        return Math.abs(C);
    }

    @Override
    public double residual() {
        return Math.abs(anchorB.worldPosition().sub(anchorA.worldPosition()).dot(normal));
    }
}
