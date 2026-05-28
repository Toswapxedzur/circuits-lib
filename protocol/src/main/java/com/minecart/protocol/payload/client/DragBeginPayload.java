package com.minecart.protocol.payload.client;

import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadRegistry;
import com.minecart.protocol.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Client → server: opens a drag-lease for {@link #getElementIds()} under {@link #getGestureId()}.
 * The lease persists until the matching {@link DragEndPayload} arrives (or the channel closes).
 *
 * <h2>Why explicit Begin / End</h2>
 * The cascade engine and panel-driven mutators are all preflight-then-apply atomic operations, so
 * <em>within</em> one mutation the registry could be polled and released around the operation. The
 * problem is the multi-frame, multi-payload nature of a drag: a touch-down on Client A reserves
 * the element, then Client A streams updates over many frames; Client B's attempt to touch the
 * same element midstream needs to be refused without any single payload being able to express
 * "I'll be back". An explicit Begin gives the server an upfront notice that the lease is wanted
 * for an open-ended duration; End closes it.
 *
 * <p>The server's response to a Begin <em>could</em> fail (lease conflict) — Phase 2c+ will plumb
 * a per-channel {@code DragReplyPayload} so the client can roll back its optimistic prediction.
 * For now the integrated server has a single client, so failures don't happen in practice; the
 * field is reserved on the wire.
 */
public final class DragBeginPayload implements Payload {

    public static final String PAYLOAD_ID = ProtocolStrings.PAYLOAD_DRAG_BEGIN;

    public static final PayloadType<DragBeginPayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, DragBeginPayload::new);

    private UUID worldId;
    private UUID gestureId;
    private final List<UUID> elementIds = new ArrayList<>();

    public DragBeginPayload() {
    }

    public DragBeginPayload(UUID worldId, UUID gestureId, List<UUID> elementIds) {
        this.worldId = worldId;
        this.gestureId = gestureId;
        if (elementIds != null) {
            this.elementIds.addAll(elementIds);
        }
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
    public List<UUID> getElementIds() { return Collections.unmodifiableList(elementIds); }

    @Override
    public void save(CompoundTag tag) {
        Payload.super.save(tag);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_WORLD_ID, worldId);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_GESTURE_ID, gestureId);
        TagUtil.putUuidList(tag, ProtocolStrings.TAG_ELEMENT_IDS, elementIds);
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
        elementIds.clear();
        TagUtil.readUuidList(tag, ProtocolStrings.TAG_ELEMENT_IDS, elementIds);
        if (elementIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "DragBeginPayload must carry at least one '" + ProtocolStrings.TAG_ELEMENT_IDS + "'");
        }
    }
}
