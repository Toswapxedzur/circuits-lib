package com.minecart.physics.constraint;

/**
 * A single constraint between rigid bodies. Constraints are the unit of work for the Gauss-Seidel
 * projection loop: in each solver iteration, every constraint is asked to {@link #project} a
 * single nudge that brings its endpoints closer to satisfaction, and to report its current
 * residual via {@link #residual()}. The solver accumulates residuals to decide when to stop
 * iterating.
 *
 * <h2>Projection contract</h2>
 * <ul>
 *   <li>Implementations mutate the connected bodies' poses directly via the {@code Body} setters
 *       / {@code Body.translate} / {@code Body.rotate} APIs. No new bodies are created and no
 *       side state is recorded across iterations.</li>
 *   <li>The projection should be a single linearised step toward the constraint manifold — not a
 *       full Newton-Raphson solve. Multiple iterations are how the solver converges.</li>
 *   <li>The correction is distributed across the endpoint bodies in inverse-inertia proportion,
 *       so a body with {@code invMassT == 0} (or {@code invMassR == 0}) is automatically left
 *       alone and the other side absorbs the entire correction.</li>
 *   <li>Numerically degenerate states (zero-length axis, both endpoints locked, …) must be
 *       silently no-op'd rather than throwing — the solver will see {@code residual()} unchanged
 *       and decide based on its global stopping criterion.</li>
 * </ul>
 *
 * <h2>Residual contract</h2>
 * <ul>
 *   <li>{@link #residual()} returns a non-negative scalar measuring how far the constraint is
 *       from satisfaction in the current pose. Zero means satisfied to machine precision.</li>
 *   <li>The unit is constraint-specific (a distance constraint returns a world-space length, a
 *       pin joint returns the gap between its two endpoints), but values are comparable across
 *       constraints within a solve because the solver only uses the sum / max for the stop
 *       criterion.</li>
 *   <li>The function must be a pure read of body poses — no mutation, no caching of cross-call
 *       state. Calling {@code residual()} twice without an intervening {@code project()} must
 *       return the same value.</li>
 * </ul>
 */
public interface Constraint {

    /**
     * Performs one Gauss-Seidel projection iteration. Returns the residual <em>before</em> this
     * projection — i.e. how unsatisfied the constraint was at the start of the call. The solver
     * uses this return value (rather than calling {@link #residual()} separately) so a single
     * combined read-and-correct can be the hot-path body for one iteration.
     */
    double project();

    /**
     * Returns the constraint's residual against the current body poses. See class javadoc for the
     * scalar's semantics. Implementations must not mutate any state in this call.
     */
    double residual();
}
