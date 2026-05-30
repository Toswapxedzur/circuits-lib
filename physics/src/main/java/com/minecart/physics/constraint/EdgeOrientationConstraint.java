package com.minecart.physics.constraint;

import com.minecart.physics.AnchorPoint;
import com.minecart.physics.Body;
import com.minecart.physics.Vec2;

import java.util.Objects;

/**
 * Keeps two anchors parallel to an authored direction while allowing length changes. This is the
 * edge-lock counterpart of a rigid distance constraint: it constrains orientation only, not length.
 */
public final class EdgeOrientationConstraint implements Constraint {

    private static final double EPSILON = 1e-12;

    private final AnchorPoint anchorA;
    private final AnchorPoint anchorB;
    private final Vec2 normal;

    public EdgeOrientationConstraint(AnchorPoint anchorA, AnchorPoint anchorB, Vec2 direction) {
        this.anchorA = Objects.requireNonNull(anchorA, "anchorA");
        this.anchorB = Objects.requireNonNull(anchorB, "anchorB");
        Objects.requireNonNull(direction, "direction");
        if (direction.lengthSquared() < EPSILON) {
            throw new IllegalArgumentException("direction must be non-zero");
        }
        Vec2 unit = direction.normalised();
        this.normal = unit.perpendicular();
    }

    public static EdgeOrientationConstraint atCurrentDirection(AnchorPoint anchorA, AnchorPoint anchorB) {
        Vec2 d = anchorB.worldPosition().sub(anchorA.worldPosition());
        return new EdgeOrientationConstraint(anchorA, anchorB, d);
    }

    @Override
    public double project() {
        Vec2 wA = anchorA.worldPosition();
        Vec2 wB = anchorB.worldPosition();
        double C = wB.sub(wA).dot(normal);
        if (Math.abs(C) < EPSILON) {
            return 0.0;
        }

        Body bodyA = anchorA.body();
        Body bodyB = anchorB.body();
        Vec2 rA = wA.sub(bodyA.rotationPivot());
        Vec2 rB = wB.sub(bodyB.rotationPivot());

        double crossA = rA.cross(normal);
        double crossB = rB.cross(normal);
        double wTotal = bodyA.invMassT() + bodyA.invMassR() * crossA * crossA
                + bodyB.invMassT() + bodyB.invMassR() * crossB * crossB;
        if (wTotal == 0.0) {
            return Math.abs(C);
        }

        double lambda = -C / wTotal;
        bodyA.translate(normal.scale(-lambda));
        bodyB.translate(normal.scale(lambda));
        bodyA.rotate(-lambda * crossA);
        bodyB.rotate(lambda * crossB);
        return Math.abs(C);
    }

    @Override
    public double residual() {
        return Math.abs(anchorB.worldPosition().sub(anchorA.worldPosition()).dot(normal));
    }
}
