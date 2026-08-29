package com.minecart.display.entity;

/**
 * A fixed-timestep accumulator. Feed it the variable render-frame {@code dt}; it returns how many <b>fixed</b>
 * steps to run this frame, so the simulation advances at a constant rate independent of frame rate.
 *
 * <p>This is the <b>physics clock</b> — deliberately SEPARATE from the electrical-signal tick (the circuit solve,
 * which is event-driven off board edits and runs on the server's own 20 Hz logic tick). Physics needs a steady
 * high-rate substep for stable rigid-body contacts; the electric sim does not. Keeping them on independent clocks
 * (owner directive) means neither is hostage to the other's rate.
 */
public final class WorldClock {

    private final float step;      // seconds per fixed step
    private final int maxSteps;    // clamp: most steps to run in one frame (avoids the spiral of death)
    private float accumulator;

    /** @param hz         fixed steps per second (e.g. 60)
     *  @param maxCatchUp max fixed steps to run in a single frame after a stall */
    public WorldClock(float hz, int maxCatchUp) {
        this.step = 1f / hz;
        this.maxSteps = maxCatchUp;
    }

    /** Adds {@code dt} to the accumulator and returns how many fixed steps to run now (0..maxCatchUp). */
    public int advance(float dt) {
        accumulator += Math.min(dt, step * maxSteps); // clamp incoming dt so a long stall can't queue a flood
        int steps = 0;
        while (accumulator >= step && steps < maxSteps) {
            accumulator -= step;
            steps++;
        }
        return steps;
    }

    /** The fixed step length in seconds. */
    public float step() {
        return step;
    }
}
