package com.minecart.logic;

import com.minecart.registry.AllComponents;
import com.minecart.variant.Informations;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Reproduces H5: a {@link ServerCircuit} that contains two disconnected battery/resistor loops must
 * still solve — each connected component gets its own grounded reference node. Before the fix only the
 * first node of the whole circuit was grounded, leaving the other component's voltages unconstrained,
 * making the matrix singular so {@code solve()} failed and every element was zeroed each tick.
 */
class DisconnectedSubgraphSolveTest {

    /** Builds a p->q battery (2V) + q->p resistor (1 Ohm) loop; steady current magnitude is ~2.0A. */
    private CircuitEdge buildLoop(ServerWorld world) {
        CircuitNode p = world.createNode(AllComponents.CONNECTION);
        CircuitNode q = world.createNode(AllComponents.CONNECTION);
        world.connect(AllComponents.BATTERY, p, q, new Informations.BatteryInfo(2.0, 1e-9));
        return world.connect(AllComponents.RESISTOR, q, p, new Informations.ResistorInfo(1.0));
    }

    @Test
    void twoDisconnectedLoopsInOneCircuitBothSolve() {
        ServerLevel level = new ServerLevel();
        ServerWorld world = level.createWorld();

        CircuitEdge res1 = buildLoop(world);
        CircuitEdge res2 = buildLoop(world);

        // Fuse the two independent loops into a SINGLE circuit without electrically connecting them,
        // so the circuit holds two disconnected components (the scenario that used to go singular).
        ServerCircuit c1 = (ServerCircuit) res1.getCircuit();
        ServerCircuit c2 = (ServerCircuit) res2.getCircuit();
        c2.mergeInto(c1);
        world.removeCircuit(c2);
        c1.markDirty();

        level.tick();

        assertEquals(2.0, Math.abs(res1.getCurrent().getValue()), 1e-6);
        assertEquals(2.0, Math.abs(res2.getCurrent().getValue()), 1e-6);
    }

    @Test
    void removingBridgeKeepsStillPoweredLoopEnergized() {
        ServerLevel level = new ServerLevel();
        ServerWorld world = level.createWorld();

        // Loop 1
        CircuitNode p1 = world.createNode(AllComponents.CONNECTION);
        CircuitNode q1 = world.createNode(AllComponents.CONNECTION);
        world.connect(AllComponents.BATTERY, p1, q1, new Informations.BatteryInfo(2.0, 1e-9));
        CircuitEdge res1 = world.connect(AllComponents.RESISTOR, q1, p1, new Informations.ResistorInfo(1.0));

        // Loop 2
        CircuitNode p2 = world.createNode(AllComponents.CONNECTION);
        CircuitNode q2 = world.createNode(AllComponents.CONNECTION);
        world.connect(AllComponents.BATTERY, p2, q2, new Informations.BatteryInfo(2.0, 1e-9));
        CircuitEdge res2 = world.connect(AllComponents.RESISTOR, q2, p2, new Informations.ResistorInfo(1.0));

        // Bridge the two loops at a single node so they share one circuit. A lone connecting edge
        // carries no current (no return path), so both loops still run at ~2A.
        CircuitEdge bridge = world.connect(AllComponents.RESISTOR, q1, p2, new Informations.ResistorInfo(1.0));
        assertNotNull(bridge);
        assertEquals(res1.getCircuit(), res2.getCircuit(), "bridge should have fused both loops into one circuit");

        level.tick();
        assertEquals(2.0, Math.abs(res1.getCurrent().getValue()), 1e-6);
        assertEquals(2.0, Math.abs(res2.getCurrent().getValue()), 1e-6);

        // Remove the bridge. Disconnect no longer splits the circuit, so the circuit now holds two
        // disconnected components. The still-powered loop must keep its ~2A rather than being zeroed.
        world.disconnect(bridge);
        level.tick();

        assertEquals(2.0, Math.abs(res1.getCurrent().getValue()), 1e-6);
        assertEquals(2.0, Math.abs(res2.getCurrent().getValue()), 1e-6);
    }

    @Test
    void connectRejectsSelfLoop() {
        ServerLevel level = new ServerLevel();
        ServerWorld world = level.createWorld();
        CircuitNode n = world.createNode(AllComponents.CONNECTION);
        // B9: connect must reject node1 == node2, mirroring changeEdgeEndpoint.
        assertThrows(IllegalArgumentException.class,
                () -> world.connect(AllComponents.RESISTOR, n, n));
    }
}
