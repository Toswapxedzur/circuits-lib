package com.minecart.spice;

import com.minecart.elements.edge.Capacitor;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerCircuit;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllComponents;
import com.minecart.variant.Informations;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The point of the ngspice backend: the per-tick error is bounded by the solver's tolerance and does
 * NOT grow with simulated time. An RC charge is compared against its closed form at 1 s and at 10 s;
 * the explicit-Euler tick the built-in solver used drifts by O(dt) per tick and fails the tight bound.
 */
class SpiceSolverAccuracyTest {
    private static final double R = 1.0, C = 1.0, V = 10.0;

    @Test
    void rcChargeErrorStaysBoundedOverTime() {
        assumeTrue(ServerCircuit.SPICE_BACKEND, "ngspice backend not active");
        ServerLevel level = new ServerLevel();
        double dt = level.getTickRate();
        ServerWorld world = level.createWorld();
        CircuitNode a = world.createNode(AllComponents.CONNECTION);
        CircuitNode b = world.createNode(AllComponents.CONNECTION);
        world.connect(AllComponents.BATTERY, a, b, new Informations.BatteryInfo(V, 1e-9));
        Capacitor cap = (Capacitor) world.connect(AllComponents.CAPACITOR, a, b, new Informations.CapacitorInfo(C, R));

        double worstEarly = 0, worstLate = 0;
        int ticks = (int) Math.round(10.0 / dt);
        for (int i = 1; i <= ticks; i++) {
            level.tick();
            double t = i * dt;
            double exact = C * V * (1 - Math.exp(-t / (R * C)));
            double err = Math.abs(cap.get().getCharge() - exact);
            if (t <= 1.0) worstEarly = Math.max(worstEarly, err);
            else worstLate = Math.max(worstLate, err);
        }
        // Absolute error stays within solver tolerance in both windows (Q_max = 10).
        assertTrue(worstEarly < 2e-3, "early error " + worstEarly);
        assertTrue(worstLate < 2e-3, "late error " + worstLate);
        // …and it does not grow: the late window is no worse than the early one (plus noise).
        assertTrue(worstLate <= worstEarly + 1e-4, "error grew with time: early=" + worstEarly + " late=" + worstLate);
        assertEquals(C * V, cap.get().getCharge(), 1e-3);
        assertEquals(0.0, cap.getCurrent().getValue(), 1e-3);
    }

    @Test
    void voltageDividerIsExact() {
        assumeTrue(ServerCircuit.SPICE_BACKEND, "ngspice backend not active");
        ServerLevel level = new ServerLevel();
        ServerWorld world = level.createWorld();
        CircuitNode top = world.createNode(AllComponents.CONNECTION);
        CircuitNode mid = world.createNode(AllComponents.CONNECTION);
        CircuitNode bottom = world.createNode(AllComponents.CONNECTION);
        world.connect(AllComponents.BATTERY, top, bottom, new Informations.BatteryInfo(12.0, 1e-9));
        var r1 = world.connect(AllComponents.RESISTOR, top, mid, new Informations.ResistorInfo(100.0));
        var r2 = world.connect(AllComponents.RESISTOR, mid, bottom, new Informations.ResistorInfo(200.0));
        level.tick();
        double i = 12.0 / 300.0;
        assertEquals(i, r1.getCurrent().getValue(), 1e-9);
        assertEquals(i, r2.getCurrent().getValue(), 1e-9);
        assertEquals(12.0 * 200.0 / 300.0, mid.getVoltage().getValue() - bottom.getVoltage().getValue(), 1e-9);
    }
}
