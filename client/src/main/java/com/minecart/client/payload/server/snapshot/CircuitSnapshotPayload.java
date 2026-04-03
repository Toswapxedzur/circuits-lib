package com.minecart.client.payload.server.snapshot;

import com.minecart.client.payload.Payload;
import com.minecart.client.payload.PayloadRegistry;
import com.minecart.client.payload.PayloadType;
import com.minecart.logic.Circuit;
import com.minecart.logic.World;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.Tag;

import java.util.UUID;

/**
 * Data for a full circuit snapshot (client resync). Wire format here; client apply is {@link CircuitSnapshotHandler}.
 */
public final class CircuitSnapshotPayload implements Payload {

    public static final String PAYLOAD_ID = "minecart.circuit_snapshot_payload";

    public static final PayloadType<CircuitSnapshotPayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, CircuitSnapshotPayload::new);

    private static final String TAG_WORLD_ID = "world_id";
    static final String TAG_CIRCUIT = "circuit";

    private UUID worldId;
    private CompoundTag circuitData;

    public CircuitSnapshotPayload() {
    }

    public CircuitSnapshotPayload(UUID worldId, CompoundTag circuitData) {
        this.worldId = worldId;
        this.circuitData = circuitData;
    }

    public static CircuitSnapshotPayload capture(World world, Circuit circuit) {
        CompoundTag tag = new CompoundTag();
        circuit.save(tag);
        UUID wid = world != null ? world.getId() : null;
        return new CircuitSnapshotPayload(wid, tag);
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

    public CompoundTag getCircuitData() {
        return circuitData;
    }

    public void setCircuitData(CompoundTag circuitData) {
        this.circuitData = circuitData;
    }

    @Override
    public void save(CompoundTag tag) {
        Payload.writeEnvelope(tag, this);
        TagUtil.putUUID(tag, TAG_WORLD_ID, worldId);
        if (circuitData != null) {
            tag.put(TAG_CIRCUIT, circuitData.copy());
        }
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.verifyEnvelope(tag, getPayloadId());
        worldId = TagUtil.getUUID(tag, TAG_WORLD_ID);
        Tag t = tag.get(TAG_CIRCUIT);
        circuitData = TagUtil.requireCompoundTag(t, "Missing or invalid '" + TAG_CIRCUIT + "'");
    }
}
