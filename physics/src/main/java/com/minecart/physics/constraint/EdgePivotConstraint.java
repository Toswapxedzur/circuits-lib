package com.minecart.physics.constraint;

import com.minecart.physics.AnchorPoint;
import com.minecart.physics.Body;
import com.minecart.physics.Vec2;

import java.util.Objects;

/**
 * Keeps an edge line passing through a fixed world pivot while allowing the line to rotate and
 * stretch. This constrains the pivot relationship only; combine with {@link DistanceConstraint}
 * when the edge length must also be preserved.
 */
public final class EdgePivotConstraint implements Constraint {

    private final AnchorPoint anchorA;
    private final AnchorPoint anchorB;
    private final Vec2 pivot;

    public EdgePivotConstraint(AnchorPoint anchorA, AnchorPoint anchorB, Vec2 pivot) {
        this.anchorA = Objects.requireNonNull(anchorA, "anchorA");
        this.anchorB = Objects.requireNonNull(anchorB, "anchorB");
        this.pivot = Objects.requireNonNull(pivot, "pivot");
    }

    @Override
    public double project() {
        Vec2 wA = anchorA.worldPosition();
        Vec2 wB = anchorB.worldPosition();
        Vec2 edge = wB.sub(wA);
        if (edge.lengthSquared() < ConstraintSupport.EPSILON) {
            return 0.0;
        }
        Vec2 normal = edge.normalised().perpendicular();
        Vec2 midpoint = wA.add(wB).scale(0.5);
        double C = midpoint.sub(pivot).dot(normal);
        if (Math.abs(C) < ConstraintSupport.EPSILON) {
            return 0.0;
        }

        Body bodyA = anchorA.body();
        Body bodyB = anchorB.body();
        Vec2 rA = wA.sub(bodyA.rotationPivot());
        Vec2 rB = wB.sub(bodyB.rotationPivot());

        double crossA = rA.cross(normal);
        double crossB = rB.cross(normal);
        // C is measured from the midpoint, so each endpoint contributes half of the displacement.
        double wTotal = 0.25 * (bodyA.invMassT() + bodyA.invMassR() * crossA * crossA
                + bodyB.invMassT() + bodyB.invMassR() * crossB * crossB);
        if (wTotal == 0.0) {
            return Math.abs(C);
        }

        double lambda = -C / wTotal;
        bodyA.translate(normal.scale(0.5 * lambda));
        bodyB.translate(normal.scale(0.5 * lambda));
        bodyA.rotate(0.5 * lambda * crossA);
        bodyB.rotate(0.5 * lambda * crossB);
        return Math.abs(C);
    }

    @Override
    public double residual() {
        Vec2 wA = anchorA.worldPosition();
        Vec2 wB = anchorB.worldPosition();
        Vec2 edge = wB.sub(wA);
        if (edge.lengthSquared() < ConstraintSupport.EPSILON) {
            return 0.0;
        }
        Vec2 normal = edge.normalised().perpendicular();
        Vec2 midpoint = wA.add(wB).scale(0.5);
        return Math.abs(midpoint.sub(pivot).dot(normal));
    }
}
