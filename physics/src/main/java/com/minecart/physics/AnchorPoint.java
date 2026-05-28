package com.minecart.physics;

import java.util.Objects;

/**
 * A point fixed in a {@link Body}'s local frame, used as the endpoint of constraints. The local
 * offset is constant for the lifetime of the {@code AnchorPoint}; the {@link #worldPosition()}
 * read accessor evaluates the offset against the body's current pose on each call, so the value
 * tracks any solver updates to the body without caller intervention.
 *
 * <p>Conceptually:
 * <pre>
 *   worldPosition = body.position + R(body.angle) * localOffset
 * </pre>
 * where {@code R(theta)} is the standard 2D rotation matrix. For a body whose anchor is its centre
 * itself, callers pass {@link Vec2#ZERO} as the local offset and the world position is just the
 * body's translational position.
 *
 * <h2>Why a separate type</h2>
 * Constraints could in principle reference {@code (Body, Vec2 localOffset)} directly, but
 * encapsulating it here means constraint code is uniform whether the endpoint is a body's centre,
 * a port on a component, or any other attached point. It also gives a natural home for the
 * world-position computation, which would otherwise be duplicated at every constraint's
 * projection site.
 *
 * <p>Anchor points are immutable value objects modulo the body reference: same body and same
 * local offset means same world position will be reported (as the body moves). The body itself is
 * mutable — that's the entire point of the solver.
 */
public final class AnchorPoint {

    private final Body body;
    private final Vec2 localOffset;

    public AnchorPoint(Body body, Vec2 localOffset) {
        this.body = Objects.requireNonNull(body, "body");
        this.localOffset = Objects.requireNonNull(localOffset, "localOffset");
    }

    /** Anchor at the body's centre — most common case. */
    public static AnchorPoint atCentre(Body body) {
        return new AnchorPoint(body, Vec2.ZERO);
    }

    public Body body() {
        return body;
    }

    public Vec2 localOffset() {
        return localOffset;
    }

    /**
     * Resolves the anchor against the body's current pose. Re-evaluated on each call, so this
     * tracks any concurrent solver updates to the body without explicit notification.
     */
    public Vec2 worldPosition() {
        return body.localToWorld(localOffset);
    }

    @Override
    public String toString() {
        return "Anchor[body=" + body.id() + " local=" + localOffset
                + " world=" + worldPosition() + "]";
    }
}
