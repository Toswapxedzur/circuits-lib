package com.minecart.protocol.payload.client;

import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadRegistry;
import com.minecart.protocol.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Client → server: "connect {@code startNodeId} to {@code endNodeId} with a {@link com.minecart.logic.CircuitEdge}
 * of {@code elementTypeId} inside {@code worldId}". Both node ids must already exist in the world (free node or
 * a component port). The server-side handler is a no-op if either id is missing.
 */
public final class ConnectEdgePayload implements Payload {

    public static final String PAYLOAD_ID = ProtocolStrings.PAYLOAD_CONNECT_EDGE;

    public static final PayloadType<ConnectEdgePayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, ConnectEdgePayload::new);

    private UUID worldId;
    private String elementTypeId;
    private UUID startNodeId;
    private UUID endNodeId;

    public ConnectEdgePayload() {
    }

    public ConnectEdgePayload(UUID worldId, String elementTypeId, UUID startNodeId, UUID endNodeId) {
        this.worldId = worldId;
        this.elementTypeId = elementTypeId;
        this.startNodeId = startNodeId;
        this.endNodeId = endNodeId;
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
    public UUID getStartNodeId() { return startNodeId; }
    public UUID getEndNodeId() { return endNodeId; }

    @Override
    public void save(CompoundTag tag) {
        Payload.super.save(tag);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_WORLD_ID, worldId);
        tag.putString(ProtocolStrings.TAG_ELEMENT_TYPE_ID, elementTypeId == null ? "" : elementTypeId);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_START_NODE_ID, startNodeId);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_END_NODE_ID, endNodeId);
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.super.load(tag);
        worldId = Payload.requireUUID(tag, ProtocolStrings.TAG_WORLD_ID);
        elementTypeId = tag.getString(ProtocolStrings.TAG_ELEMENT_TYPE_ID);
        if (elementTypeId == null || elementTypeId.isEmpty()) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_ELEMENT_TYPE_ID + "'");
        }
        startNodeId = TagUtil.getUUID(tag, ProtocolStrings.TAG_START_NODE_ID);
        endNodeId = TagUtil.getUUID(tag, ProtocolStrings.TAG_END_NODE_ID);
        if (startNodeId == null || endNodeId == null) {
            throw new IllegalArgumentException("Missing start/end node id");
        }
    }
}
