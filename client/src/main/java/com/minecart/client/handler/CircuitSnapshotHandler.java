package com.minecart.client.handler;

import com.minecart.client.logic.ClientCircuit;
import com.minecart.client.logic.ClientLevel;
import com.minecart.client.logic.ClientWorld;
import com.minecart.foundation.Circuit;
import com.minecart.misc.CoreStrings;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.server.CircuitSnapshotPayload;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.Objects;
import java.util.UUID;

/**
 * Applies {@link CircuitSnapshotPayload} on the client: replaces or inserts the circuit in a {@link ClientWorld}.
 * Only sent when a client first joined.
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
        Objects.requireNonNull(level, "level");
        CompoundTag data = Objects.requireNonNull(payload.getCircuitData(), "circuit data");
        UUID circuitId = TagUtil.getUUID(data, CoreStrings.CIRCUIT_ID);
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
        Objects.requireNonNull(world, "world");
        UUID wid = payload.getWorldId();
        if (wid != null && !wid.equals(world.getId())) {
            throw new IllegalArgumentException("World id mismatch: payload " + wid + ", target " + world.getId());
        }
        CompoundTag data = Objects.requireNonNull(payload.getCircuitData(), "circuit data");
        UUID circuitId = TagUtil.getUUID(data, CoreStrings.CIRCUIT_ID);
        Circuit existing = world.findCircuit(circuitId);
        if (existing != null) {
            world.removeCircuit(existing);
        }
        ClientCircuit circuit = new ClientCircuit(circuitId);
        world.addCircuit(circuit);
        circuit.load(world, data);
    }
}
