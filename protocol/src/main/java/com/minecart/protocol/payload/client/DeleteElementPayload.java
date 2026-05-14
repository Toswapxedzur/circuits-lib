package com.minecart.protocol.payload.client;

import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadRegistry;
import com.minecart.protocol.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Client → server: "destroy the element identified by {@code elementId} inside {@code worldId}". Used by the
 * editor's trashcan drop. Free {@link com.minecart.logic.CircuitNode}s are removed via
 * {@link com.minecart.logic.ServerWorld#destroy(com.minecart.logic.CircuitNode)} and
 * {@link com.minecart.logic.CircuitComponent}s via {@link com.minecart.logic.CircuitComponent#destroy(com.minecart.logic.ServerCircuit)}.
 * Silent no-op on unknown ids.
 */
public final class DeleteElementPayload implements Payload {

    public static final String PAYLOAD_ID = ProtocolStrings.PAYLOAD_DELETE_ELEMENT;

    public static final PayloadType<DeleteElementPayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, DeleteElementPayload::new);

    private UUID worldId;
    private UUID elementId;

    public DeleteElementPayload() {
    }

    public DeleteElementPayload(UUID worldId, UUID elementId) {
        this.worldId = worldId;
        this.elementId = elementId;
    }

    @Override
    public String getPayloadId() {
        return PAYLOAD_ID;
    }

    @Override
    public Destination getDestination() {
        return Destination.SERVER;
    }

    public UUID getWorldId() { return worldId; }
    public UUID getElementId() { return elementId; }

    @Override
    public void save(CompoundTag tag) {
        Payload.super.save(tag);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_WORLD_ID, worldId);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_ELEMENT_ID, elementId);
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.super.load(tag);
        worldId = TagUtil.getUUID(tag, ProtocolStrings.TAG_WORLD_ID);
        if (worldId == null) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_WORLD_ID + "'");
        }
        elementId = TagUtil.getUUID(tag, ProtocolStrings.TAG_ELEMENT_ID);
        if (elementId == null) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_ELEMENT_ID + "'");
        }
    }
}
