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
 * or a {@link com.minecart.logic.CircuitComponent}) inside {@code worldId} to ({@code x}, {@code y})".
 *
 * <h2>Gesture id</h2>
 * Each drag mints a fresh {@code gestureId} (UUID) at {@code touchDown}; every streaming and final
 * Move payload from that drag carries the same id. The server's drag aggregator uses it to
 * (a) coalesce multiple same-flush samples to the latest cursor pose, and (b) detect contention
 * when two distinct gesture ids target the same element in the same flush. {@code null} is allowed
 * for one-shot panel-save callers that don't run through the aggregator's contention machinery;
 * such payloads still go through the same handler but are applied as discrete hard-pin moves.
 */
public final class MoveElementPayload implements Payload {

    public static final String PAYLOAD_ID = ProtocolStrings.PAYLOAD_MOVE_ELEMENT;

    public static final PayloadType<MoveElementPayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, MoveElementPayload::new);

    private UUID worldId;
    private UUID elementId;
    private UUID gestureId;
    private double x;
    private double y;
    private boolean hasAnchorLocal;
    private double anchorLocalX;
    private double anchorLocalY;

    public MoveElementPayload() {
    }

    public MoveElementPayload(UUID worldId, UUID elementId, double x, double y) {
        this(worldId, elementId, null, x, y);
    }

    public MoveElementPayload(UUID worldId, UUID elementId, UUID gestureId, double x, double y) {
        this(worldId, elementId, gestureId, x, y, false, 0.0, 0.0);
    }

    public MoveElementPayload(UUID worldId, UUID elementId, UUID gestureId, double x, double y,
                              double anchorLocalX, double anchorLocalY) {
        this(worldId, elementId, gestureId, x, y, true, anchorLocalX, anchorLocalY);
    }

    private MoveElementPayload(UUID worldId, UUID elementId, UUID gestureId, double x, double y,
                               boolean hasAnchorLocal, double anchorLocalX, double anchorLocalY) {
        this.worldId = worldId;
        this.elementId = elementId;
        this.gestureId = gestureId;
        this.x = x;
        this.y = y;
        this.hasAnchorLocal = hasAnchorLocal;
        this.anchorLocalX = anchorLocalX;
        this.anchorLocalY = anchorLocalY;
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
    public UUID getGestureId() { return gestureId; }
    public double getX() { return x; }
    public double getY() { return y; }
    public boolean hasAnchorLocal() { return hasAnchorLocal; }
    public double getAnchorLocalX() { return anchorLocalX; }
    public double getAnchorLocalY() { return anchorLocalY; }

    @Override
    public void save(CompoundTag tag) {
        Payload.super.save(tag);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_WORLD_ID, worldId);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_ELEMENT_ID, elementId);
        if (gestureId != null) {
            TagUtil.putUUID(tag, ProtocolStrings.TAG_GESTURE_ID, gestureId);
        }
        tag.putDouble(ProtocolStrings.TAG_X, x);
        tag.putDouble(ProtocolStrings.TAG_Y, y);
        if (hasAnchorLocal) {
            tag.putDouble(ProtocolStrings.TAG_ANCHOR_LOCAL_X, anchorLocalX);
            tag.putDouble(ProtocolStrings.TAG_ANCHOR_LOCAL_Y, anchorLocalY);
        }
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
        gestureId = TagUtil.getUUID(tag, ProtocolStrings.TAG_GESTURE_ID);
        x = tag.getDouble(ProtocolStrings.TAG_X);
        y = tag.getDouble(ProtocolStrings.TAG_Y);
        hasAnchorLocal = tag.get(ProtocolStrings.TAG_ANCHOR_LOCAL_X) != null
                && tag.get(ProtocolStrings.TAG_ANCHOR_LOCAL_Y) != null;
        if (hasAnchorLocal) {
            anchorLocalX = tag.getDouble(ProtocolStrings.TAG_ANCHOR_LOCAL_X);
            anchorLocalY = tag.getDouble(ProtocolStrings.TAG_ANCHOR_LOCAL_Y);
        } else {
            anchorLocalX = 0.0;
            anchorLocalY = 0.0;
        }
    }
}
