package com.minecart.physics;

import java.util.Objects;

/**
 * A 2D rigid body in the constraint solver. Carries a mutable pose {@code (x, y, theta)} and two
 * inverse-inertia scalars ({@code invMassT} for translation, {@code invMassR} for rotation around
 * its centre). Each body has a {@code rotationPivot} — the world-space point its rotation occurs
 * around, separate from the body's translational position. For a free body this defaults to the
 * body's own position and tracks it via {@link #setPosition(Vec2)}; for a pivot-constrained body
 * (the "rotation-free" lock state in the domain layer) the caller sets it once and the solver
 * leaves it alone.
 *
 * <h2>Inverse inertias</h2>
 * Following the standard rigid-body-dynamics convention, a body's resistance to motion along each
 * axis is encoded as the <em>inverse</em> of its inertia ("infinite mass" is {@code 0.0} rather
 * than a sentinel). This makes the projection arithmetic in constraints clean: corrections
 * naturally scale by the inverse mass, so a locked body simply contributes {@code 0} weight to
 * any distribution and the other side absorbs the full correction.
 *
 * <ul>
 *   <li>{@code invMassT == 0}: the body cannot translate (positional lock). Used for permanently
 *       anchored bodies and for the {@code ROTATION_FREE} lock state.</li>
 *   <li>{@code invMassT >  0}: the body translates with weight proportional to its value.
 *       Uniform mass = {@code 1.0}.</li>
 *   <li>{@code invMassR == 0}: the body cannot rotate. Used for permanently anchored bodies and
 *       for the {@code POSITION_FREE} lock state.</li>
 *   <li>{@code invMassR >  0}: the body rotates with weight proportional to its value.</li>
 * </ul>
 *
 * <p>The four canonical combinations (mirroring the domain layer's {@code LockMode}) are
 * pre-built via {@link #free(Object, Vec2)}, {@link #locked(Object, Vec2)},
 * {@link #positionFree(Object, Vec2)}, and {@link #rotationFree(Object, Vec2, Vec2)}, but callers
 * can construct arbitrary {@code (invMassT, invMassR)} pairs via the full constructor for
 * weighted-inertia experiments.
 *
 * <h2>Identity</h2>
 * Each body carries a caller-supplied identifier (typically a {@code UUID} from the domain layer,
 * but typed as {@link Object} so this module stays independent). Two bodies are equal iff their
 * identifiers are equal. This identity rule is what lets constraints reference bodies indirectly
 * through {@link AnchorPoint}, and is what the solver uses to deduplicate when the same body is
 * pulled in by multiple constraints in the same iteration.
 */
public final class Body {

    private final Object id;
    private Vec2 position;
    private double angle;
    private Vec2 rotationPivot;
    private double invMassT;
    private double invMassR;

    public Body(Object id, Vec2 position, double angle, Vec2 rotationPivot,
                double invMassT, double invMassR) {
        this.id = Objects.requireNonNull(id, "id");
        this.position = Objects.requireNonNull(position, "position");
        this.angle = angle;
        this.rotationPivot = Objects.requireNonNull(rotationPivot, "rotationPivot");
        if (invMassT < 0.0 || invMassR < 0.0) {
            throw new IllegalArgumentException(
                    "inverse inertias must be non-negative; got T=" + invMassT + ", R=" + invMassR);
        }
        this.invMassT = invMassT;
        this.invMassR = invMassR;
    }

    /** Fully free body: translates and rotates around its own position with unit inverse inertia. */
    public static Body free(Object id, Vec2 position) {
        return new Body(id, position, 0.0, position, 1.0, 1.0);
    }

    /** Permanently anchored body. Used for the {@code LOCKED} state and for external anchor points. */
    public static Body locked(Object id, Vec2 position) {
        return new Body(id, position, 0.0, position, 0.0, 0.0);
    }

    /**
     * Translation-only body: can move freely but cannot rotate (the {@code POSITION_FREE} state in
     * the domain layer's lock vocabulary).
     */
    public static Body positionFree(Object id, Vec2 position) {
        return new Body(id, position, 0.0, position, 1.0, 0.0);
    }

    /**
     * Rotation-only body around a specified pivot: cannot translate, but rotates around the given
     * world-space pivot (the {@code ROTATION_FREE} state). The pivot is in world coordinates and
     * does NOT move when the body's {@link #angle} changes; the position rotates around it.
     */
    public static Body rotationFree(Object id, Vec2 position, Vec2 pivot) {
        return new Body(id, position, 0.0, pivot, 0.0, 1.0);
    }

    public Object id() {
        return id;
    }

    public Vec2 position() {
        return position;
    }

    public double angle() {
        return angle;
    }

    public Vec2 rotationPivot() {
        return rotationPivot;
    }

    public double invMassT() {
        return invMassT;
    }

    public double invMassR() {
        return invMassR;
    }

    public void setPosition(Vec2 position) {
        this.position = Objects.requireNonNull(position, "position");
    }

    public void setAngle(double angle) {
        this.angle = angle;
    }

    public void setRotationPivot(Vec2 rotationPivot) {
        this.rotationPivot = Objects.requireNonNull(rotationPivot, "rotationPivot");
    }

    public void setInvMassT(double invMassT) {
        if (invMassT < 0.0) {
            throw new IllegalArgumentException("invMassT must be non-negative; got " + invMassT);
        }
        this.invMassT = invMassT;
    }

    public void setInvMassR(double invMassR) {
        if (invMassR < 0.0) {
            throw new IllegalArgumentException("invMassR must be non-negative; got " + invMassR);
        }
        this.invMassR = invMassR;
    }

    /**
     * Translates by {@code delta}, scaled by {@code invMassT}. The scaling means a locked body
     * ({@code invMassT == 0}) is unaffected; callers don't need to special-case the lock state.
     *
     * <p>The body's {@link #rotationPivot} is moved by the same applied delta. This keeps the
     * rotation reference frame attached to the body for any body that can translate — which is
     * what the constraint Jacobian assumes for the FREE and POSITION_FREE canonical lock modes.
     * Bodies that don't translate (LOCKED / ROTATION_FREE both have {@code invMassT == 0}) skip
     * the whole call, so a world-anchored pivot used by a ROTATION_FREE body is preserved.
     */
    public void translate(Vec2 delta) {
        if (invMassT == 0.0) {
            return;
        }
        Vec2 applied = delta.scale(invMassT);
        position = position.add(applied);
        rotationPivot = rotationPivot.add(applied);
    }

    /**
     * Rotates the body around its {@link #rotationPivot} by {@code radians}, scaled by
     * {@code invMassR}. The pivot itself is unchanged; the position rotates around it. As with
     * {@link #translate}, a locked body ({@code invMassR == 0}) is unaffected.
     *
     * <p>If {@code rotationPivot} coincides with {@link #position}, only {@link #angle} advances
     * (the position is the pivot, so it stays put under any rotation about itself); this is the
     * common case for a body that rotates around its own centre.
     */
    public void rotate(double radians) {
        if (invMassR == 0.0) {
            return;
        }
        double effective = radians * invMassR;
        angle += effective;
        Vec2 offset = position.sub(rotationPivot);
        if (offset.lengthSquared() == 0.0) {
            return;
        }
        position = rotationPivot.add(offset.rotate(effective));
    }

    /**
     * Computes the world-space point of a body-local offset at the body's current pose. The offset
     * is interpreted in the body's local frame BEFORE the pose's rotation is applied; the result
     * is {@code position + R(angle) * localOffset}.
     */
    public Vec2 localToWorld(Vec2 localOffset) {
        return position.add(localOffset.rotate(angle));
    }

    /**
     * Convenience for the common debug pattern of "what does this body's pose look like now". The
     * output isn't a serialisation format — just a stable {@code toString} for logs / asserts.
     */
    @Override
    public String toString() {
        return "Body[" + id + " pos=" + position + " angle=" + angle
                + " pivot=" + rotationPivot
                + " invMassT=" + invMassT + " invMassR=" + invMassR + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Body b)) return false;
        return id.equals(b.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
