package com.minecart.protocol.payload.client;

import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadRegistry;
import com.minecart.protocol.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Client → server: "create a {@link com.minecart.logic.CircuitNode} of {@code elementTypeId} at world position
 * ({@code x}, {@code y}) inside {@code worldId}". The server creates the node and replicates the resulting
 * insert back to all clients via the existing {@link com.minecart.protocol.payload.server.CircuitElementPayload}
 * stream. Server-side handler lives in {@code :server}.
 */
public final class PlaceNodePayload implements Payload {

    public static final String PAYLOAD_ID = ProtocolStrings.PAYLOAD_PLACE_NODE;

    public static final PayloadType<PlaceNodePayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, PlaceNodePayload::new);

    private UUID worldId;
    private String elementTypeId;
    private double x;
    private double y;

    public PlaceNodePayload() {
    }

    public PlaceNodePayload(UUID worldId, String elementTypeId, double x, double y) {
        this.worldId = worldId;
        this.elementTypeId = elementTypeId;
        this.x = x;
        this.y = y;
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
    public String getElementTypeId() { return elementTypeId; }
    public double getX() { return x; }
    public double getY() { return y; }

    @Override
    public void save(CompoundTag tag) {
        Payload.super.save(tag);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_WORLD_ID, worldId);
        tag.putString(ProtocolStrings.TAG_ELEMENT_TYPE_ID, elementTypeId == null ? "" : elementTypeId);
        tag.putDouble(ProtocolStrings.TAG_X, x);
        tag.putDouble(ProtocolStrings.TAG_Y, y);
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.super.load(tag);
        worldId = TagUtil.getUUID(tag, ProtocolStrings.TAG_WORLD_ID);
        if (worldId == null) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_WORLD_ID + "'");
        }
        elementTypeId = tag.getString(ProtocolStrings.TAG_ELEMENT_TYPE_ID);
        if (elementTypeId == null || elementTypeId.isEmpty()) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_ELEMENT_TYPE_ID + "'");
        }
        x = tag.getDouble(ProtocolStrings.TAG_X);
        y = tag.getDouble(ProtocolStrings.TAG_Y);
    }
}
