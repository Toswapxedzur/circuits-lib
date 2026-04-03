package com.minecart.client.payload.server.snapshot;

import com.minecart.client.logic.ClientCircuit;
import com.minecart.client.logic.ClientLevel;
import com.minecart.client.logic.ClientWorld;
import com.minecart.client.payload.PayloadHandler;
import com.minecart.logic.Circuit;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.Objects;
import java.util.UUID;

/**
 * Applies {@link CircuitSnapshotPayload} on the client: replaces or inserts the circuit in a {@link ClientWorld}.
 */
public final class CircuitSnapshotHandler implements PayloadHandler<CircuitSnapshotPayload> {

    private final ClientLevel level;

    public CircuitSnapshotHandler(ClientLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    public ClientLevel getLevel() {
        return level;
    }

    @Override
    public void handle(CircuitSnapshotPayload payload) {
        apply(payload, level);
    }

    public static void apply(CircuitSnapshotPayload payload, ClientLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("level is null");
        }
        CompoundTag data = payload.getCircuitData();
        if (data == null) {
            throw new IllegalArgumentException("circuit data is null");
        }
        UUID circuitId = TagUtil.getUUID(data, "circuit_id");
        if (circuitId == null) {
            throw new IllegalArgumentException("Missing circuit_id in snapshot");
        }
        ClientWorld world = level.getOrCreateWorld(payload.getWorldId());
        Circuit existing = world.findCircuit(circuitId);
        if (existing != null) {
            world.removeCircuit(existing);
        }
        ClientCircuit circuit = new ClientCircuit(circuitId);
        world.addCircuit(circuit);
        circuit.load(world, data);
    }

    public static void apply(CircuitSnapshotPayload payload, ClientWorld world) {
        if (world == null) {
            throw new IllegalArgumentException("world is null");
        }
        UUID wid = payload.getWorldId();
        if (wid != null && !wid.equals(world.getId())) {
            throw new IllegalArgumentException("World id mismatch: payload " + wid + ", target " + world.getId());
        }
        CompoundTag data = payload.getCircuitData();
        if (data == null) {
            throw new IllegalArgumentException("circuit data is null");
        }
        UUID circuitId = TagUtil.getUUID(data, "circuit_id");
        if (circuitId == null) {
            throw new IllegalArgumentException("Missing circuit_id in snapshot");
        }
        Circuit existing = world.findCircuit(circuitId);
        if (existing != null) {
            world.removeCircuit(existing);
        }
        ClientCircuit circuit = new ClientCircuit(circuitId);
        world.addCircuit(circuit);
        circuit.load(world, data);
    }
}
