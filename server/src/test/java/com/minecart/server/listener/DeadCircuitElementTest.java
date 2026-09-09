package com.minecart.server.listener;

import com.minecart.elements.edge.Resistor;
import com.minecart.foundation.Circuit;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.protocol.payload.server.CircuitElementPayload;
import com.minecart.registry.AllComponents;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A circuit created and REMOVED within the same tick never reaches the client (its lifecycle INSERT/REMOVE pair
 * coalesces to nothing), so no element delta may be keyed to it — the client would throw "No circuit for id"
 * and the dispatcher would close the connection. Reproduces the 2026-09-09 bug: two whole-board rebuilds in one
 * server tick (each tears down and recreates every circuit).
 */
class DeadCircuitElementTest {

    @Test
    void elementOfCircuitRemovedInSameTickIsNotReplicated() {
        ServerLevel level = new ServerLevel();
        List<CircuitElementPayload> payloads = new ArrayList<>();
        CircuitElementListener listener = new CircuitElementListener(level, payloads::add);
        listener.attach();

        ServerWorld world = level.createWorld();
        CircuitNode a = world.createNode(AllComponents.CONNECTION);
        CircuitNode b = world.createNode(AllComponents.CONNECTION);
        Resistor r = world.connect(AllComponents.RESISTOR, a, b);
        Circuit born = r.getCircuit() != null ? r.getCircuit() : a.getCircuit();
        UUID deadId = born.getId();
        for (Circuit c : new ArrayList<>(world.getCircuits())) {
            world.removeCircuit(c); // the "rebuild" tears everything down before the tick's sync
        }
        level.tick();
        listener.sync();

        for (CircuitElementPayload p : payloads) {
            assertNotEquals(deadId, p.getCircuitId(),
                    "element delta keyed to a circuit the client never learned about: " + p.getChanges());
        }
    }
}
