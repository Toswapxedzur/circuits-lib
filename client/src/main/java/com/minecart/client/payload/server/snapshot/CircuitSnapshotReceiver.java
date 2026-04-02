package com.minecart.client.payload.server.snapshot;

import com.minecart.client.logic.ClientCircuit;
import com.minecart.client.logic.ClientLevel;
import com.minecart.client.logic.ClientWorld;
import com.minecart.logic.Circuit;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.Tag;

import java.util.UUID;

/**
 * Tag read/write for {@link CircuitSnapshotPayload} and applying a snapshot onto a {@link ClientLevel}.
 */
public final class CircuitSnapshotReceiver {

    static final String TAG_WORLD_ID = "world_id";
    static final String TAG_CIRCUIT = "circuit";

    private CircuitSnapshotReceiver() {
    }

    static void writePayload(CircuitSnapshotPayload p, CompoundTag tag) {
        TagUtil.putUUID(tag, TAG_WORLD_ID, p.worldId);
        if (p.circuitData != null) {
            tag.put(TAG_CIRCUIT, p.circuitData.copy());
        }
    }

    static void readPayload(CircuitSnapshotPayload p, CompoundTag tag) {
        p.worldId = TagUtil.getUUID(tag, TAG_WORLD_ID);
        Tag t = tag.get(TAG_CIRCUIT);
        p.circuitData = TagUtil.requireCompoundTag(t, "Missing or invalid '" + TAG_CIRCUIT + "'");
    }

    /**
     * Replaces or inserts the circuit on the client: resolves {@link ClientWorld} (creating one if needed), removes an
     * existing circuit with the same {@code circuit_id}, then {@link Circuit#load}s the snapshot.
     */
    public static void apply(CircuitSnapshotPayload payload, ClientLevel level) {
        if (level == null) {
            throw new IllegalArgumentException("level is null");
        }
        CompoundTag data = payload.circuitData;
        if (data == null) {
            throw new IllegalArgumentException("circuit data is null");
        }
        UUID circuitId = TagUtil.getUUID(data, "circuit_id");
        if (circuitId == null) {
            throw new IllegalArgumentException("Missing circuit_id in snapshot");
        }
        ClientWorld world = level.getOrCreateWorld(payload.worldId);
        Circuit existing = world.findCircuit(circuitId);
        if (existing != null) {
            world.removeCircuit(existing);
        }
        ClientCircuit circuit = new ClientCircuit(circuitId);
        world.addCircuit(circuit);
        circuit.load(world, data);
    }

    /**
     * Applies the snapshot into an existing client world (must match {@link #getWorldId()} when set on the payload).
     */
    public static void apply(CircuitSnapshotPayload payload, ClientWorld world) {
        if (world == null) {
            throw new IllegalArgumentException("world is null");
        }
        UUID wid = payload.worldId;
        if (wid != null && !wid.equals(world.getId())) {
            throw new IllegalArgumentException("World id mismatch: payload " + wid + ", target " + world.getId());
        }
        CompoundTag data = payload.circuitData;
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
