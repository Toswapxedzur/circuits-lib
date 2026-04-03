package com.minecart.client.payload.server.topology;

import com.minecart.client.payload.Payload;
import com.minecart.client.payload.PayloadRegistry;
import com.minecart.client.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.ListTag;
import com.minecart.serialization.tag.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Server → client circuit topology: ordered {@link CircuitTopologyChange} steps. Serialization and binary framing are
 * defined here; client application is {@link com.minecart.client.payload.client.topology.CircuitTopologyHandler}.
 */
public final class CircuitTopologyPayload implements Payload {

    public static final String PAYLOAD_ID = "minecart.circuit_topology_payload";

    public static final PayloadType<CircuitTopologyPayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, CircuitTopologyPayload::new);

    private static final String TAG_WORLD_ID = "world_id";
    private static final String TAG_CIRCUIT_ID = "circuit_id";
    private static final String TAG_CHANGES = "changes";

    private UUID worldId;
    private UUID circuitId;
    private final List<CircuitTopologyChange> changes = new ArrayList<>();

    public CircuitTopologyPayload() {
    }

    public CircuitTopologyPayload(UUID worldId, UUID circuitId, List<CircuitTopologyChange> changes) {
        this.worldId = worldId;
        this.circuitId = circuitId;
        if (changes != null) {
            this.changes.addAll(changes);
        }
    }

    @Override
    public String getPayloadId() {
        return PAYLOAD_ID;
    }

    @Override
    public Destination getDestination() {
        return Destination.CLIENT;
    }

    public UUID getWorldId() {
        return worldId;
    }

    public void setWorldId(UUID worldId) {
        this.worldId = worldId;
    }

    public UUID getCircuitId() {
        return circuitId;
    }

    public void setCircuitId(UUID circuitId) {
        this.circuitId = circuitId;
    }

    public List<CircuitTopologyChange> getChanges() {
        return Collections.unmodifiableList(changes);
    }

    @Override
    public void save(CompoundTag tag) {
        Payload.writeEnvelope(tag, this);
        TagUtil.putUUID(tag, TAG_WORLD_ID, worldId);
        TagUtil.putUUID(tag, TAG_CIRCUIT_ID, circuitId);
        ListTag list = new ListTag();
        for (CircuitTopologyChange c : changes) {
            CompoundTag step = new CompoundTag();
            c.save(step);
            list.add(step);
        }
        tag.put(TAG_CHANGES, list);
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.verifyEnvelope(tag, getPayloadId());
        worldId = TagUtil.getUUID(tag, TAG_WORLD_ID);
        circuitId = TagUtil.getUUID(tag, TAG_CIRCUIT_ID);
        if (circuitId == null) {
            throw new IllegalArgumentException("Missing '" + TAG_CIRCUIT_ID + "'");
        }
        changes.clear();
        Tag t = tag.get(TAG_CHANGES);
        if (t instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                CompoundTag step = TagUtil.requireCompoundTag(list.get(i), TAG_CHANGES + "[" + i + "]");
                changes.add(CircuitTopologyChange.load(step));
            }
        }
    }
}
