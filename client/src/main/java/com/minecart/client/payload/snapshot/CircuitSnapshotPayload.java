package com.minecart.client.payload.snapshot;

import com.minecart.client.payload.Payload;
import com.minecart.client.payload.PayloadRegistry;
import com.minecart.client.payload.PayloadType;
import com.minecart.logic.Circuit;
import com.minecart.logic.World;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Full serialized circuit for client bootstrap or resync ({@link Circuit#save} shape under {@link CircuitSnapshotReceiver#TAG_CIRCUIT}).
 * Wire format and client application live in {@link CircuitSnapshotReceiver}.
 */
public class CircuitSnapshotPayload extends Payload {

    public static final String PAYLOAD_ID = "minecart.circuit_snapshot_payload";

    public static final PayloadType<CircuitSnapshotPayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, CircuitSnapshotPayload::new);

    UUID worldId;
    CompoundTag circuitData;

    public CircuitSnapshotPayload() {
    }

    public CircuitSnapshotPayload(UUID worldId, CompoundTag circuitData) {
        this.worldId = worldId;
        this.circuitData = circuitData;
    }

    /**
     * Captures the full circuit graph into a payload, optionally tagging the owning world's id for routing.
     */
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
    protected void savePayload(CompoundTag tag) {
        CircuitSnapshotReceiver.writePayload(this, tag);
    }

    @Override
    protected void loadPayload(CompoundTag tag) {
        CircuitSnapshotReceiver.readPayload(this, tag);
    }
}
