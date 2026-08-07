package com.minecart.server.listener;

import com.minecart.elements.edge.Resistor;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.misc.CoreStrings;
import com.minecart.protocol.payload.server.CircuitElementChange;
import com.minecart.protocol.payload.server.CircuitElementPayload;
import com.minecart.registry.AllComponents;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentReplicationTest {

    @Test
    void existingEdgeCurrentChangeIsReplicatedAfterSolve() {
        ServerLevel level = new ServerLevel();
        List<CircuitElementPayload> payloads = new ArrayList<>();
        CircuitElementListener listener = new CircuitElementListener(level, payloads::add);
        listener.attach();

        ServerWorld world = level.createWorld();
        CircuitNode a = world.createNode(AllComponents.CONNECTION);
        CircuitNode b = world.createNode(AllComponents.CONNECTION);
        Resistor resistor = world.connect(AllComponents.RESISTOR, a, b);
        level.tick();
        listener.sync();
        payloads.clear();

        world.connect(AllComponents.BATTERY, a, b);
        level.tick();
        listener.sync();

        boolean sawResistorCurrentChange = false;
        for (CircuitElementPayload payload : payloads) {
            for (CircuitElementChange change : payload.getChanges()) {
                if (change.kind() != CircuitElementChange.Kind.CHANGE) {
                    continue;
                }
                if (!resistor.getId().equals(change.elementId()) || change.data() == null) {
                    continue;
                }
                sawResistorCurrentChange = true;
                assertEquals(resistor.getCurrent().getValue(),
                        change.data().getDouble(CoreStrings.EDGE_CURRENT), 1e-9);
            }
        }
        assertTrue(sawResistorCurrentChange,
                "Existing resistor must get a CHANGE payload when solve updates its current");
    }
}
