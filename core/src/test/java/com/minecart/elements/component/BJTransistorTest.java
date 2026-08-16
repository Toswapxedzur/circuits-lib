package com.minecart.elements.component;

import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerCircuit;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllComponents;
import com.minecart.variant.Informations;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BJTransistorTest {

    @Test
    void generateBuildsYTopology() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        ServerCircuit circuit = new ServerCircuit();
        circuit.setWorld(w);
        w.addCircuit(circuit);

        BJTransistor bjt = AllComponents.BJ_TRANSISTOR.create(w);
        circuit.addComponent(bjt);
        bjt.generate();

        assertNotNull(bjt.center);
        assertNotNull(bjt.base);
        assertNotNull(bjt.collector);
        assertNotNull(bjt.emitter);
        assertNotNull(bjt.edgeBase);
        assertNotNull(bjt.edgeCollector);
        assertNotNull(bjt.edgeEmitter);
        // generate() creates 4 connected internal nodes via createNodeForComponent (each spawning its own
        // Circuit) and then 3 connectInComponent calls that merge them. The merged circuit is wherever the
        // first node landed: bjt.center.getCircuit().
        ServerCircuit internal = (ServerCircuit) bjt.center.getCircuit();
        assertEquals(3, internal.edges().size());
        assertEquals(4, internal.nodes().size());
        assertEquals(100.0, bjt.getInfo().getBeta(), 1e-9);
        assertEquals(bjt.base, bjt.getPort(0));
        assertEquals(bjt.collector, bjt.getPort(1));
        assertEquals(bjt.emitter, bjt.getPort(2));
    }

    /**
     * Reproduces H6: once the circuit collects component constitutive relations, the BJT enforces
     * {@code I_C = beta * I_B}. A common-emitter bias (base battery 11V through 9 Ohm, collector
     * supply 5V) yields I_B = 11/(1+beta+9) = 0.1A and I_C = beta*I_B = 10A for beta = 100.
     *
     * <p>Before the fix, {@link com.minecart.logic.ServerCircuit#collectRelation} never iterated
     * components, so the constitutive relation was dead and the collector current only satisfied KCL
     * (I_C = -I_B). The assertion below fails under that old behavior.
     */
    @Test
    void enforcesCollectorEqualsBetaTimesBase() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        ServerCircuit circuit = new ServerCircuit();
        circuit.setWorld(w);
        w.addCircuit(circuit);

        BJTransistor bjt = AllComponents.BJ_TRANSISTOR.create(w);
        circuit.addComponent(bjt);
        bjt.generate();
        bjt.getInfo().setBeta(100.0);

        CircuitNode base = bjt.getPort(0);
        CircuitNode collector = bjt.getPort(1);
        CircuitNode emitter = bjt.getPort(2);

        // Base bias: battery emitter -> base, 11V through 9 Ohm internal resistance.
        w.connect(AllComponents.BATTERY, emitter, base, new Informations.BatteryInfo(11.0, 9.0));
        // Collector supply: battery emitter -> collector, provides the collector current's return path.
        w.connect(AllComponents.BATTERY, emitter, collector, new Informations.BatteryInfo(5.0, 1e-3));

        level.tick();

        double iB = bjt.edgeBase.getCurrent().getValue();
        double iC = bjt.edgeCollector.getCurrent().getValue();

        // Base current is meaningfully nonzero (the solve didn't collapse to all-zero).
        assertTrue(Math.abs(iB) > 1e-3, "base current should be nonzero, was " + iB);
        // Constitutive relation holds: I_C = beta * I_B.
        assertEquals(100.0 * iB, iC, 1e-6);
        // And it is NOT the pre-fix degenerate I_C == -I_B.
        assertTrue(Math.abs(iC - (-iB)) > 1.0,
                "collector current must reflect beta gain, not merely -I_B (iB=" + iB + ", iC=" + iC + ")");
    }
}
