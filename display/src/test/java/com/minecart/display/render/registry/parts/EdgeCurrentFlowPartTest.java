package com.minecart.display.render.registry.parts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeCurrentFlowPartTest {

    @Test
    void dashSpeedIncreasesWithActualCurrentMagnitude() {
        float slow = EdgeCurrentFlowPart.dashSpeedForCurrent(0.1);
        float fast = EdgeCurrentFlowPart.dashSpeedForCurrent(10.0);

        assertTrue(fast > slow);
    }

    @Test
    void dashSpeedIsLinearUntilClamp() {
        float one = EdgeCurrentFlowPart.dashSpeedForCurrent(1.0);
        float two = EdgeCurrentFlowPart.dashSpeedForCurrent(2.0);
        float three = EdgeCurrentFlowPart.dashSpeedForCurrent(3.0);

        assertEquals(two - one, three - two, 1e-6f);
    }

    @Test
    void zeroCurrentHasNoDashSpeed() {
        assertTrue(EdgeCurrentFlowPart.dashSpeedForCurrent(0.0) == 0.0f);
    }
}
