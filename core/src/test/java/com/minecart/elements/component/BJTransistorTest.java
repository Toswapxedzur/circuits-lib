package com.minecart.elements.component;

import com.minecart.logic.ServerCircuit;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllComponents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
