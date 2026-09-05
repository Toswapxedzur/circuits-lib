package com.minecart.spice;

import com.minecart.elements.edge.Capacitor;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerCircuit;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllComponents;
import com.minecart.variant.Informations;
import org.junit.jupiter.api.Test;

/**
 * Not an assertion: prints the RC-charge error per simulated second and the mean tick cost for
 * whichever backend is active, so the two can be compared by running the suite twice
 * ({@code -Dcircuitslib.solver=ejml} vs the default ngspice). Output lands in the test report XML.
 */
class SolverDriftMeasurement {
    @Test
    void printDriftAndTickCost() {
        ServerLevel level = new ServerLevel();
        double dt = level.getTickRate();
        ServerWorld world = level.createWorld();
        CircuitNode a = world.createNode(AllComponents.CONNECTION);
        CircuitNode b = world.createNode(AllComponents.CONNECTION);
        world.connect(AllComponents.BATTERY, a, b, new Informations.BatteryInfo(10.0, 1e-9));
        Capacitor cap = (Capacitor) world.connect(AllComponents.CAPACITOR, a, b, new Informations.CapacitorInfo(1.0, 1.0));
        StringBuilder sb = new StringBuilder("backend=" + (ServerCircuit.SPICE_BACKEND ? "ngspice" : "ejml") + " dt=" + dt + "\n");
        long t0 = System.nanoTime();
        int ticks = (int) Math.round(10.0 / dt);
        double worst = 0;
        for (int i = 1; i <= ticks; i++) {
            level.tick();
            double t = i * dt;
            double err = Math.abs(cap.get().getCharge() - 10.0 * (1 - Math.exp(-t)));
            worst = Math.max(worst, err);
            if (i % (int) Math.round(1.0 / dt) == 0) { sb.append(String.format("t=%4.1fs  |err|=%.3e  worst so far=%.3e%n", t, err, worst)); }
        }
        double perTickMs = (System.nanoTime() - t0) / 1e6 / ticks;
        sb.append(String.format("mean tick cost = %.3f ms%n", perTickMs));
        System.out.println(sb);
    }
}
