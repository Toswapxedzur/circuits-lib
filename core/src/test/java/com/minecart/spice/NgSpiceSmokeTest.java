package com.minecart.spice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Loads libngspice through JNA and runs a textbook RC charge; skipped when the library is absent. */
class NgSpiceSmokeTest {
    @Test
    void rcChargeMatchesAnalyticSolution() {
        assumeTrue(NgSpice.available(), "libngspice not installed");
        NgSpice ng = NgSpice.get();
        // 10 V through 1 Ohm into 1 F, from 0 V: v(t) = 10 (1 - e^{-t}).
        assertTrue(ng.loadCircuit(List.of(
                "rc smoke",
                "V1 in 0 dc 10",
                "R1 in out 1",
                "C1 out 0 1 ic=0",
                ".tran 1m 0.5 uic",
                ".end")), "netlist should load: " + ng.drainErrors());
        assertTrue(ng.command("run"), "run should succeed: " + ng.drainErrors());
        Double t = ng.lastValue("time");
        Double v = ng.lastValue("v(out)");
        assertNotNull(t); assertNotNull(v);
        assertEquals(0.5, t, 1e-9);
        assertEquals(10 * (1 - Math.exp(-0.5)), v, 1e-3);
        // Current through the source: -(10 - v)/1 (flows out of the + terminal).
        Double i = ng.lastValue("i(v1)");
        assertNotNull(i);
        assertEquals(-(10 - v), i, 1e-3);
    }
}
