package com.minecart.client.sync;

import com.minecart.client.handler.server.CircuitElementHandler;
import com.minecart.client.logic.ClientCircuit;
import com.minecart.client.logic.ClientLevel;
import com.minecart.client.logic.ClientWorld;
import com.minecart.client.network.SyncRegistry;
import com.minecart.client.payload.Payload;
import com.minecart.client.payload.server.CircuitElementChange;
import com.minecart.client.payload.server.CircuitElementPayload;
import com.minecart.elements.edge.Resistor;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.registry.AllComponents;
import com.minecart.serialization.tag.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Exercises server → client element replication: {@link CircuitElementPayload}, {@link CircuitElementChange},
 * {@link CircuitElementHandler}, and {@link SyncRegistry} default sync (element {@code save}/{@code load}).
 */
class ClientSyncTest {

    @Test
    void changeStepAppliesSyncDataViaHandler() {
        ClientLevel level = new ClientLevel();
        ClientWorld world = level.createWorld();
        ClientCircuit circuit = new ClientCircuit();
        circuit.setWorld(world);
        world.addCircuit(circuit);

        CircuitNode n1 = AllComponents.CONNECTION.create(world);
        CircuitNode n2 = AllComponents.CONNECTION.create(world);
        circuit.insertNode(n1);
        circuit.insertNode(n2);
        Resistor edge = world.connect(AllComponents.RESISTOR, n1, n2);

        edge.getCurrent().setValue(0.0);
        edge.getCurrent().setValue(12.5);
        CircuitElementChange change = CircuitElementChange.changeFromSync(edge);
        edge.getCurrent().setValue(0.0);

        CircuitElementPayload payload =
                new CircuitElementPayload(world.getId(), circuit.getId(), List.of(change));
        CircuitElementHandler handler = new CircuitElementHandler(level);
        handler.handle(payload);

        assertEquals(12.5, edge.getCurrent().getValue(), 1e-12);
    }

    @Test
    void insertStepAddsSerializedNode() {
        ClientLevel level = new ClientLevel();
        ClientWorld world = level.createWorld();
        ClientCircuit circuit = new ClientCircuit();
        circuit.setWorld(world);
        world.addCircuit(circuit);

        CircuitNode template = AllComponents.CONNECTION.create(world);
        UUID nodeId = template.getId();
        CompoundTag insertData = CircuitElement.serialize(template);

        CircuitElementPayload payload = new CircuitElementPayload(
                world.getId(),
                circuit.getId(),
                List.of(CircuitElementChange.insert(AllComponents.CONNECTION.getTypeId(), insertData)));

        new CircuitElementHandler(level).handle(payload);

        CircuitNode resolved = circuit.findNode(nodeId);
        assertNotNull(resolved);
        assertEquals(nodeId, resolved.getId());
    }

    @Test
    void circuitElementPayloadTagRoundTrip() {
        UUID worldId = UUID.randomUUID();
        UUID circuitId = UUID.randomUUID();

        CircuitElementPayload original =
                new CircuitElementPayload(worldId, circuitId, List.of(CircuitElementChange.remove(UUID.randomUUID())));

        CompoundTag root = new CompoundTag();
        original.save(root);

        CircuitElementPayload decoded = Payload.deserialize(root, CircuitElementPayload.class);
        assertEquals(original.getWorldId(), decoded.getWorldId());
        assertEquals(original.getCircuitId(), decoded.getCircuitId());
        assertEquals(1, decoded.getChanges().size());
        assertEquals(CircuitElementChange.Kind.REMOVE, decoded.getChanges().getFirst().kind());
    }

    @Test
    void syncRegistryDefaultUsesElementSaveLoad() {
        ClientWorld world = new ClientWorld(new ClientLevel());
        Resistor r = AllComponents.RESISTOR.create(world);
        r.getCurrent().setValue(3.25);

        CompoundTag tag = new CompoundTag();
        SyncRegistry.writeSyncData(r, tag);
        r.getCurrent().setValue(0.0);
        SyncRegistry.readSyncData(r, tag);

        assertEquals(3.25, r.getCurrent().getValue(), 1e-12);
    }
}
