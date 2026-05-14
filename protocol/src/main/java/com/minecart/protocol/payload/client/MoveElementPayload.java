package com.minecart.protocol.payload.client;

import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadRegistry;
import com.minecart.protocol.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Client → server: "translate the element identified by {@code elementId} (a free {@link com.minecart.logic.CircuitNode}
 * or a {@link com.minecart.logic.CircuitComponent}) inside {@code worldId} to ({@code x}, {@code y})". For
 * components the server re-stamps every internal port node via {@link com.minecart.registry.ComponentAnchorRegistry}
 * and recentres any non-port internal nodes. Silently no-ops on unknown world / element / element kind.
 */
public final class MoveElementPayload implements Payload {

    public static final String PAYLOAD_ID = ProtocolStrings.PAYLOAD_MOVE_ELEMENT;

    public static final PayloadType<MoveElementPayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, MoveElementPayload::new);

    private UUID worldId;
    private UUID elementId;
    private double x;
    private double y;

    public MoveElementPayload() {
    }

    public MoveElementPayload(UUID worldId, UUID elementId, double x, double y) {
        this.worldId = worldId;
        this.elementId = elementId;
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
    public UUID getElementId() { return elementId; }
    public double getX() { return x; }
    public double getY() { return y; }

    @Override
    public void save(CompoundTag tag) {
        Payload.super.save(tag);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_WORLD_ID, worldId);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_ELEMENT_ID, elementId);
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
        elementId = TagUtil.getUUID(tag, ProtocolStrings.TAG_ELEMENT_ID);
        if (elementId == null) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_ELEMENT_ID + "'");
        }
        x = tag.getDouble(ProtocolStrings.TAG_X);
        y = tag.getDouble(ProtocolStrings.TAG_Y);
    }
}
