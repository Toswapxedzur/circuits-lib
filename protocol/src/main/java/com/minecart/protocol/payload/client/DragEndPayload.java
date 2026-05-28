package com.minecart.protocol.payload.client;

import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadRegistry;
import com.minecart.protocol.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Client → server: closes the drag-lease opened by a matching {@link DragBeginPayload} carrying the
 * same {@link #getGestureId()}. Idempotent on the server: ending a gesture that holds no leases is
 * a no-op (handy for defensive end-on-disconnect dispatch).
 *
 * <p>Doesn't carry the element list — the registry remembers which elements were acquired under
 * the gesture, so releasing by gestureId alone is enough. Saves bytes on the wire too.
 */
public final class DragEndPayload implements Payload {

    public static final String PAYLOAD_ID = ProtocolStrings.PAYLOAD_DRAG_END;

    public static final PayloadType<DragEndPayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, DragEndPayload::new);

    private UUID worldId;
    private UUID gestureId;

    public DragEndPayload() {
    }

    public DragEndPayload(UUID worldId, UUID gestureId) {
        this.worldId = worldId;
        this.gestureId = gestureId;
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
    public UUID getGestureId() { return gestureId; }

    @Override
    public void save(CompoundTag tag) {
        Payload.super.save(tag);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_WORLD_ID, worldId);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_GESTURE_ID, gestureId);
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.super.load(tag);
        worldId = TagUtil.getUUID(tag, ProtocolStrings.TAG_WORLD_ID);
        if (worldId == null) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_WORLD_ID + "'");
        }
        gestureId = TagUtil.getUUID(tag, ProtocolStrings.TAG_GESTURE_ID);
        if (gestureId == null) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_GESTURE_ID + "'");
        }
    }
}
