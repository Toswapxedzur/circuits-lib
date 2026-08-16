package com.minecart.physics;

import com.minecart.physics.constraint.Constraint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Gauss-Seidel position-based dynamics (PBD) solver. Drives a list of {@link Constraint}s toward
 * satisfaction by repeatedly projecting each one against the current body poses; reads on body
 * poses inside a constraint always see the latest writes from prior constraints in the same
 * iteration, so the standard Gauss-Seidel convergence properties hold.
 *
 * <h2>Stop conditions</h2>
 * The solver iterates until the smaller of:
 * <ul>
 *   <li>The configured maximum iteration count ({@code maxIterations}), or</li>
 *   <li>The summed residual across all constraints drops below {@code residualTolerance}.</li>
 * </ul>
 * No rollback or "refuse if infeasible" logic — the caller commits whatever pose the solver
 * arrives at. For a circuit editor this is the right default: cursor-unreachable configurations
 * settle into a visibly-resisted pose rather than rejecting the drag outright.
 *
 * <h2>Determinism</h2>
 * The solver iterates the {@code constraints} list in its given order. Two solves with the same
 * inputs and same ordering produce bit-identical outputs. Callers that need cross-run / cross-
 * client reproducibility should pre-sort the constraint list by a stable key before handing it in.
 *
 * <h2>Allocations</h2>
 * The solver itself allocates nothing in its hot loop — {@link #solve(SolverConfig, List)} only
 * touches the {@code constraints} list and the bodies it transitively references. Constraint
 * implementations are expected to be allocation-free in their {@code project()} methods (small
 * {@link Vec2} value objects do allocate, but JIT scalarisation typically elides them).
 */
public final class Solver {

    private static final Logger log = LoggerFactory.getLogger(Solver.class);

    private Solver() {}

    /**
     * Drives {@code constraints} to convergence under {@code config}. Returns the
     * {@link SolverResult} describing how many iterations ran and the final summed residual.
     *
     * <p>The body poses referenced by the constraints are mutated in place; no other state is
     * touched. An empty constraint list is a no-op that returns immediately with zero iterations
     * and zero residual.
     */
    public static SolverResult solve(SolverConfig config, List<? extends Constraint> constraints) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(constraints, "constraints");
        if (constraints.isEmpty()) {
            return new SolverResult(0, 0.0, true);
        }

        double lastResidual = Double.POSITIVE_INFINITY;
        int iterations = 0;
        for (; iterations < config.maxIterations(); iterations++) {
            double summed = 0.0;
            // Standard Gauss-Seidel sweep: each project() reads the latest body poses produced by
            // earlier constraints in this iteration, so corrections propagate one hop per pass.
            for (Constraint c : constraints) {
                summed += c.project();
            }
            lastResidual = summed;
            if (summed <= config.residualTolerance()) {
                // Converged: bump iteration count to reflect we did *this* pass too.
                iterations++;
                if (log.isTraceEnabled()) {
                    log.trace("solver converged in {} iterations, residual={}", iterations, summed);
                }
                return new SolverResult(iterations, summed, true);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("solver hit iteration cap {} without convergence; residual={}",
                    config.maxIterations(), lastResidual);
        }
        return new SolverResult(iterations, lastResidual, false);
    }
}
