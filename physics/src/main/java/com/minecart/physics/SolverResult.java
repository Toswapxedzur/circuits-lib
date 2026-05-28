package com.minecart.physics;

/**
 * Result of a single {@link Solver#solve} invocation. Reports how many Gauss-Seidel iterations
 * ran, the summed residual across all constraints at exit, and whether the solver hit the
 * configured tolerance ({@code converged == true}) or bailed out at the iteration cap
 * ({@code converged == false}).
 *
 * <p>{@code converged == false} is not a failure: PBD always produces a usable pose, just one
 * that may have visible residual. Callers can use this signal to render a "linkage at limit"
 * indicator, log warnings during testing, or trigger an adaptive iteration-count bump on the
 * next solve.
 */
public record SolverResult(int iterations, double residual, boolean converged) {
}
