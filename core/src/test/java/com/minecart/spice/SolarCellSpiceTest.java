package com.minecart.spice;

import com.minecart.elements.edge.SolarCell;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerCircuit;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllComponents;
import com.minecart.variant.Informations.ResistorInfo;
import com.minecart.variant.Informations.SolarCellInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * S1 verification for the {@link SolarCell}: ngspice must solve the single-diode PV I–V curve. With a near-short
 * load the delivered current equals the (light-scaled) short-circuit current; with a near-open load the terminal
 * voltage rises to the open-circuit voltage; darkness kills the output; and halving the irradiance halves the
 * short-circuit current. Template: {@link SpiceSolverAccuracyTest}.
 */
class SolarCellSpiceTest {

    private record Rig(ServerLevel level, ServerWorld world, SolarCellInfo info, CircuitEdge cell, CircuitEdge load) {}

    /** A solar cell (+ at {@code a}) across a resistive load; settle a few ticks. */
    private static Rig rig(double loadOhms, double irradiance) {
        ServerLevel level = new ServerLevel();
        ServerWorld world = level.createWorld();
        CircuitNode a = world.createNode(AllComponents.CONNECTION);
        CircuitNode b = world.createNode(AllComponents.CONNECTION);
        SolarCellInfo info = ((SolarCell) AllComponents.SOLAR_CELL.create(world)).getDefault();
        info.setIrradiance(irradiance);
        CircuitEdge cell = world.connect(AllComponents.SOLAR_CELL, a, b, info);
        CircuitEdge load = world.connect(AllComponents.RESISTOR, a, b, new ResistorInfo(loadOhms));
        for (int i = 0; i < 3; i++) level.tick();
        return new Rig(level, world, info, cell, load);
    }

    @Test
    void shortCircuitDeliversTheFullSunPhotocurrent() {
        assumeTrue(ServerCircuit.SPICE_BACKEND, "ngspice backend not active");
        Rig r = rig(0.01, 1.0); // near-short load, full sun
        double iLoad = Math.abs(r.load().getCurrent().getValue());
        // Isc = iscFullSun (0.1 A) at irradiance 1; a tiny load draws essentially all of it.
        assertEquals(0.1, iLoad, 5e-3, "short-circuit current should equal the full-sun photocurrent (0.1 A)");
    }

    @Test
    void openCircuitRisesToVoc() {
        assumeTrue(ServerCircuit.SPICE_BACKEND, "ngspice backend not active");
        Rig r = rig(1e9, 1.0); // near-open load, full sun
        double v = Math.abs(terminalVolts(r));
        // Voc = n·Vt·ln(Iph/I0 + 1) ≈ 6 · 0.02585 · ln(1e8) ≈ 2.86 V. Allow model/temperature slack.
        assertTrue(v > 2.4 && v < 3.3, "open-circuit voltage should be ≈ Voc (2.86 V), was " + v);
    }

    @Test
    void darknessProducesNoOutput() {
        assumeTrue(ServerCircuit.SPICE_BACKEND, "ngspice backend not active");
        Rig r = rig(100.0, 0.0); // a real load, but no light
        assertEquals(0.0, Math.abs(r.load().getCurrent().getValue()), 1e-4, "a dark cell delivers ~no current");
    }

    @Test
    void halfSunHalvesTheShortCircuitCurrent() {
        assumeTrue(ServerCircuit.SPICE_BACKEND, "ngspice backend not active");
        double full = Math.abs(rig(0.01, 1.0).load().getCurrent().getValue());
        double half = Math.abs(rig(0.01, 0.5).load().getCurrent().getValue());
        assertEquals(full * 0.5, half, 5e-3, "short-circuit current scales linearly with irradiance");
    }

    private static double terminalVolts(Rig r) {
        // Voltage across the cell's two nodes.
        CircuitNode a = r.cell().getStart(), b = r.cell().getEnd();
        return a.getVoltage().getValue() - b.getVoltage().getValue();
    }
}
