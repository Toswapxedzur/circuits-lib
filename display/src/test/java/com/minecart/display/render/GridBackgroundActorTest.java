package com.minecart.display.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the grid's line-count math (H10). The old draw path looped with a {@code float}
 * accumulator ({@code for (float x = start; x <= right; x += step)}). Once {@code ulp(x) > step} — which
 * happens at extreme zoom/pan, e.g. zoom {@code 1e-7} centred near {@code x=1000} where {@code ulp(1000f)}
 * (~6e-5) dwarfs a sub-microstep — {@code x += step} stops advancing and the loop never terminates,
 * hard-freezing the GL thread. The fix computes an integer line count and iterates with a {@code double}
 * position, so the loop is always bounded and terminating. These tests are headless (pure math, no GL).
 */
class GridBackgroundActorTest {

    /**
     * Reproduces the exact numbers the freeze occurred at: zoom {@code 1e-7} with the camera near
     * {@code x=1000}. Under the old float loop this scenario spins forever; here it must complete within
     * the timeout and produce a bounded, finite set of line positions.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void gridLinesTerminateAndStayBoundedAtExtremeZoom() {
        float zoom = 1e-7f;
        float screenPx = 800f;
        float unitsPerPixel = 1f / 64f;
        float halfSpan = screenPx * unitsPerPixel * zoom * 0.5f;
        float centre = 1000f;
        float left = centre - halfSpan;
        float right = centre + halfSpan;

        float majorStep = GridBackgroundActor.pickMajorStep(2f * halfSpan);
        assertTrue(Float.isFinite(majorStep) && majorStep > 0f, "major step must be finite & positive");
        float minorStep = majorStep / 5f;

        // ulp(1000f) must exceed the step for this to be a genuine reproduction of the hang condition.
        assertTrue(Math.ulp(centre) > minorStep,
                "test must exercise the ulp(x) > step regime that hung the old float loop");

        double startMinor = Math.ceil(left / minorStep) * minorStep;
        int count = GridBackgroundActor.stepCount(startMinor, right, minorStep);
        assertTrue(count >= 0 && count <= 10_000, "line count must be bounded, was " + count);

        // Iterate exactly the way draw() now does; every position must be finite and the loop must end.
        int drawn = 0;
        for (int i = 0; i < count; i++) {
            float x = (float) (startMinor + i * (double) minorStep);
            assertTrue(Float.isFinite(x));
            drawn++;
        }
        assertEquals(count, drawn);
    }

    /** A degenerate tiny step over a wide span is clamped to the safety cap instead of exploding. */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void stepCountClampsDegenerateStep() {
        int count = GridBackgroundActor.stepCount(0.0, 1.0, 1e-9f);
        assertEquals(10_000, count, "an absurd line count must be capped");
    }

    /** Guards against NaN/negative/zero inputs producing junk counts. */
    @Test
    void stepCountRejectsBadInputs() {
        assertEquals(0, GridBackgroundActor.stepCount(Double.NaN, 1.0, 1f));
        assertEquals(0, GridBackgroundActor.stepCount(0.0, Double.NaN, 1f));
        assertEquals(0, GridBackgroundActor.stepCount(0.0, 1.0, 0f));
        assertEquals(0, GridBackgroundActor.stepCount(0.0, 1.0, -1f));
        assertEquals(0, GridBackgroundActor.stepCount(5.0, 1.0, 1f), "end < start => no lines");
    }

    /** Ordinary zoom still yields the expected, inclusive line count. */
    @Test
    void stepCountIsInclusiveForNormalRange() {
        // 0, 1, 2, ... 10  => 11 lines.
        assertEquals(11, GridBackgroundActor.stepCount(0.0, 10.0, 1f));
    }
}
