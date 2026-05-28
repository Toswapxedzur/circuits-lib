package com.minecart.protocol.payload.client;

import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadRegistry;
import com.minecart.protocol.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Client → server: rotate an element by {@code deltaRadians} around the world-space point
 * {@code (pivotX, pivotY)}. Used by the scroll-rotate and 1-second right-press gestures — both
 * pivot at the cursor's world position rather than the element's centre.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>Server-side handler routes the request through {@link com.minecart.logic.cascade.CombineCascadeEngine#tryRotateComponent
 *       CombineCascadeEngine.tryRotateComponent} after first updating the element's strict
 *       {@link com.minecart.variant.info.LockInfo} so the pivot coincides with the requested one
 *       (the engine refuses if the strict pivot disagrees, per the ROTATION_FREE pivot rule). This
 *       matches the user-spec "gestures update the strictLock state".</li>
 *   <li>Currently scoped to {@link com.minecart.logic.CircuitComponent}; edge rotation requires
 *       per-endpoint cascade and is parked behind a TODO in the handler.</li>
 *   <li>Silent no-op on unknown world/element, on a lock conflict, or on a non-component target.
 *       The client mirrors the lock-conflict outcome by reading the unchanged authoritative state
 *       on the next sync pulse — same forgiving-protocol policy as the other panel-edit payloads.</li>
 * </ul>
 *
 * <p>Delta is per-event (one scroll notch worth, or one frame of drag motion), NOT a cumulative
 * angle, so the server doesn't have to reason about ordering when concurrent gestures collide.
 */
public final class RotateElementPayload implements Payload {

    public static final String PAYLOAD_ID = ProtocolStrings.PAYLOAD_ROTATE_ELEMENT;

    public static final PayloadType<RotateElementPayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, RotateElementPayload::new);

    private UUID worldId;
    private UUID elementId;
    private double pivotX;
    private double pivotY;
    private double deltaRadians;

    public RotateElementPayload() {
    }

    public RotateElementPayload(UUID worldId, UUID elementId,
                                double pivotX, double pivotY, double deltaRadians) {
        this.worldId = worldId;
        this.elementId = elementId;
        this.pivotX = pivotX;
        this.pivotY = pivotY;
        this.deltaRadians = deltaRadians;
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
    public double getPivotX() { return pivotX; }
    public double getPivotY() { return pivotY; }
    public double getDeltaRadians() { return deltaRadians; }

    @Override
    public void save(CompoundTag tag) {
        Payload.super.save(tag);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_WORLD_ID, worldId);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_ELEMENT_ID, elementId);
        tag.putDouble(ProtocolStrings.TAG_PIVOT_X, pivotX);
        tag.putDouble(ProtocolStrings.TAG_PIVOT_Y, pivotY);
        tag.putDouble(ProtocolStrings.TAG_DELTA_RADIANS, deltaRadians);
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
        pivotX = tag.getDouble(ProtocolStrings.TAG_PIVOT_X);
        pivotY = tag.getDouble(ProtocolStrings.TAG_PIVOT_Y);
        deltaRadians = tag.getDouble(ProtocolStrings.TAG_DELTA_RADIANS);
    }
}
