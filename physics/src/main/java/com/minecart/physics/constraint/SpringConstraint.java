package com.minecart.physics.constraint;

import com.minecart.physics.AnchorPoint;
import com.minecart.physics.Body;
import com.minecart.physics.Vec2;

import java.util.Objects;

/**
 * Soft "pull this anchor toward a world-space target" constraint. The constraint function is the
 * vector displacement {@code C = anchor - target}; satisfaction means the anchor coincides with the
 * target. Unlike {@link DistanceConstraint} this constraint operates on ONE anchored body against a
 * fixed world point — the target is not on any body, it's a goal pose chosen by the caller. The
 * canonical use is "drag this body's centre (or some other point on it) toward the user's cursor".
 *
 * <h2>Compliance / softness (XPBD)</h2>
 * The constraint carries an Extended PBD {@code compliance} parameter ({@code alpha}). With
 * {@code compliance == 0} the projection is a hard pin — one iteration drives the anchor exactly
 * to the target. With {@code compliance > 0} the projection applies a softened correction whose
 * accumulated Lagrange multiplier {@code lambdaAccum} resists further pulling, giving the
 * constraint the steady-state behaviour of a linear spring: at equilibrium, the spring force
 * (proportional to {@code lambdaAccum}) balances whatever competing forces other constraints apply
 * at the anchor.
 *
 * <p>The per-iteration XPBD update is the standard one (with substep size {@code h} folded into
 * {@code alpha}, since the solver doesn't substep):
 * <pre>
 *   ΔΛ = (-C - α·Λ) / (w + α)
 *   apply ΔΛ along n (the constraint axis, anchor-to-target direction reversed)
 *   Λ += ΔΛ
 * </pre>
 * where {@code w} is the generalised inverse mass at the anchor along {@code n}.
 *
 * <h2>Choosing compliance</h2>
 * The {@code alpha} value is dimensional — its scale depends on what world units the bodies live
 * in and how stiff "stiff" should feel. For the editor at 1 unit ≈ 1 grid cell:
 * <ul>
 *   <li>{@code 0.0}: hard pin, indistinguishable from setting {@code invMass = 0} on the anchor.</li>
 *   <li>{@code 0.001}: very stiff. Single-drag visually behaves like a hard pin; multi-body
 *       configurations with rigid couplings still let the rigid edges win (since they're at
 *       {@code alpha = 0}). This is the default the integration layer uses.</li>
 *   <li>{@code >= 1.0}: visibly soft. The dragged body noticeably lags the cursor under heavy load
 *       (long rigid chains, contention). Worth tuning here for "tactile" drag feel.</li>
 * </ul>
 *
 * <h2>Per-solve state</h2>
 * The accumulator {@code lambdaAccum} is reset to {@code 0} at construction. A {@code SpringConstraint}
 * is therefore single-solve: the integration layer builds a fresh constraint each tick (the target
 * changes anyway, since it's the latest cursor sample), so reuse across solves would mean stale
 * accumulator state. The {@link #setTarget(Vec2)} setter is provided for callers that genuinely
 * want to update the target mid-solve, but the {@code lambdaAccum} is not auto-reset — this is the
 * "I know what I'm doing" path.
 *
 * <h2>What this constraint does NOT do</h2>
 * <ul>
 *   <li>No angular component. The spring acts purely on the anchor's translational position.
 *       Rotation of the body emerges only as a byproduct of the body's angular inverse inertia
 *       and the lever arm from the rotation pivot to the anchor — same as for
 *       {@link DistanceConstraint}.</li>
 *   <li>No directional pull (e.g. "spring along this axis only"). The constraint axis is always
 *       the line from target to anchor; the caller picks the anchor location to bias direction.</li>
 *   <li>No damping. PBD-flavoured projections are inherently first-order in displacement; adding
 *       a velocity-coupled term would require a velocity model that the position-only solver
 *       deliberately omits.</li>
 * </ul>
 */
public final class SpringConstraint implements Constraint {

    private static final double EPSILON = 1e-12;

    private final AnchorPoint anchor;
    private Vec2 target;
    private final double compliance;
    private double lambdaAccum;

    public SpringConstraint(AnchorPoint anchor, Vec2 target, double compliance) {
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.target = Objects.requireNonNull(target, "target");
        if (compliance < 0.0) {
            throw new IllegalArgumentException(
                    "compliance must be non-negative; got " + compliance);
        }
        this.compliance = compliance;
        this.lambdaAccum = 0.0;
    }

    public AnchorPoint anchor() {
        return anchor;
    }

    public Vec2 target() {
        return target;
    }

    public double compliance() {
        return compliance;
    }

    /**
     * Updates the world-space target. The accumulator is NOT reset — see class javadoc. Callers
     * that want a clean spring should construct a new {@code SpringConstraint} instead.
     */
    public void setTarget(Vec2 target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    /**
     * Resets the XPBD Lagrange-multiplier accumulator to zero. Use when reusing the same constraint
     * instance across logically separate solves; the integration layer normally builds fresh
     * constraints each tick and doesn't need this.
     */
    public void resetAccumulator() {
        this.lambdaAccum = 0.0;
    }

    @Override
    public double project() {
        Vec2 worldAnchor = anchor.worldPosition();
        Vec2 d = worldAnchor.sub(target);
        double dist = d.length();

        if (dist < EPSILON) {
            // Anchor coincides with target. No axis to push along, but residual is already zero —
            // the spring is at rest, nothing to do.
            return 0.0;
        }

        Vec2 n = d.scale(1.0 / dist);
        double C = dist;

        Body body = anchor.body();
        Vec2 r = worldAnchor.sub(body.rotationPivot());
        double cross = r.cross(n);

        double wT = body.invMassT();
        double wR = body.invMassR();
        // Generalised inverse mass at the anchor along axis n. Same form as DistanceConstraint's
        // wTotal but with one body only.
        double w = wT + wR * cross * cross;

        double denom = w + compliance;
        if (denom == 0.0) {
            // Anchor's body is fully locked AND there's no compliance to soak up the residual.
            // The spring physically can't pull; report the residual and let the solver decide.
            return C;
        }

        // XPBD step. With compliance == 0 the alpha·lambdaAccum term drops out and we recover the
        // standard PBD distance projection; with compliance > 0 the accumulator grows each
        // iteration and resists further correction, giving the spring its restoring-force feel at
        // steady state.
        double dLambda = (-C - compliance * lambdaAccum) / denom;
        lambdaAccum += dLambda;

        // n points from target to anchor; dLambda is typically negative (C > 0 means anchor past
        // target), so translating by n * dLambda moves the anchor along -n, toward the target.
        body.translate(n.scale(dLambda));
        // Same lever-arm rotation contribution as DistanceConstraint — Body.rotate scales by
        // invMassR internally so we hand it the raw multiplier.
        body.rotate(dLambda * cross);

        return C;
    }

    @Override
    public double residual() {
        return anchor.worldPosition().sub(target).length();
    }

    @Override
    public String toString() {
        return "SpringConstraint[" + anchor + " -> " + target
                + " alpha=" + compliance + " lambda=" + lambdaAccum + "]";
    }
}
