package com.minecart.logic;

import com.minecart.registry.AllComponents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ElectricalSolveTest {

    @Test
    void twoNodesOneResistorSolvesZeroCurrentAfterTick() {
        ServerLevel level = new ServerLevel();
        ServerWorld world = level.createWorld();
        CircuitNode a = world.createNode(AllComponents.CONNECTION);
        CircuitNode b = world.createNode(AllComponents.CONNECTION);
        CircuitEdge resistor = world.connect(AllComponents.RESISTOR, a, b);
        assertNotNull(resistor);

        level.tick();

        assertEquals(0.0, resistor.getCurrent().getValue(), 1e-9);
    }

    @Test
    void failedSolveResetsVoltagesToZero() {
        ServerLevel level = new ServerLevel();
        ServerWorld world = level.createWorld();
        CircuitNode a = world.createNode(AllComponents.CONNECTION);
        CircuitNode b = world.createNode(AllComponents.CONNECTION);
        ServerCircuit c1 = (ServerCircuit) a.getCircuit();
        ServerCircuit c2 = (ServerCircuit) b.getCircuit();
        c2.mergeInto(c1);
        world.removeCircuit(c2);
        c1.markDirty();

        a.getVoltage().setValue(12.0);
        b.getVoltage().setValue(-7.0);
        level.tick();

        assertEquals(0.0, a.getVoltage().getValue(), 1e-9);
        assertEquals(0.0, b.getVoltage().getValue(), 1e-9);
    }
}
