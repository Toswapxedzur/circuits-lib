package com.minecart.physics;

/**
 * Configuration for a single {@link Solver#solve} invocation. Holds the iteration cap and
 * residual tolerance — the two knobs that trade off convergence quality against per-call cost.
 *
 * <h2>Defaults</h2>
 * The {@link #defaults()} preset ({@code maxIterations=20}, {@code residualTolerance=1e-4}) is
 * tuned for the editor-scale workloads this module targets: small networks (tens of bodies,
 * tens of constraints), low single-digit milliseconds per solve, and tight enough convergence
 * that residuals don't accumulate visibly between drag ticks. Callers can dial either knob
 * independently via {@link #withMaxIterations} / {@link #withResidualTolerance}.
 *
 * <h2>Choosing iterations</h2>
 * As a rule of thumb, the iteration count should match the graph diameter of the constraint
 * network: each Gauss-Seidel pass propagates corrections one hop along the constraint graph. For
 * short chains and small loops (the editor's common case) the default {@code 20} converges to
 * floating-point precision; long chains or deeply nested loops may want more.
 *
 * <h2>Choosing tolerance</h2>
 * In editor world units (where 1 unit ≈ one grid cell), {@code 1e-4} is well below visible
 * pixel precision at any reasonable zoom. Tighter tolerances are usually wasted work; looser
 * tolerances admit visible residuals (gaps between joints, mismatched bar lengths).
 */
public final class SolverConfig {

    private final int maxIterations;
    private final double residualTolerance;

    public SolverConfig(int maxIterations, double residualTolerance) {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be >= 1; got " + maxIterations);
        }
        if (residualTolerance < 0.0) {
            throw new IllegalArgumentException(
                    "residualTolerance must be non-negative; got " + residualTolerance);
        }
        this.maxIterations = maxIterations;
        this.residualTolerance = residualTolerance;
    }

    /** Editor-tuned default: 20 iterations, {@code 1e-4} residual tolerance. */
    public static SolverConfig defaults() {
        return new SolverConfig(20, 1e-4);
    }

    public int maxIterations() {
        return maxIterations;
    }

    public double residualTolerance() {
        return residualTolerance;
    }

    public SolverConfig withMaxIterations(int maxIterations) {
        return new SolverConfig(maxIterations, residualTolerance);
    }

    public SolverConfig withResidualTolerance(double residualTolerance) {
        return new SolverConfig(maxIterations, residualTolerance);
    }

    @Override
    public String toString() {
        return "SolverConfig[maxIterations=" + maxIterations
                + ", residualTolerance=" + residualTolerance + "]";
    }
}
