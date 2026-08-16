package com.minecart.protocol.payload.client;

import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadRegistry;
import com.minecart.protocol.payload.PayloadType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.UUID;

/**
 * Client → server: "create a {@link com.minecart.logic.CircuitComponent} of {@code elementTypeId} at world
 * position ({@code x}, {@code y}) with rotation {@code angle} (radians) inside {@code worldId}". The server
 * also positions each of the component's internal port nodes via
 * {@link com.minecart.registry.ComponentAnchorRegistry}.
 */
public final class PlaceComponentPayload implements Payload {

    public static final String PAYLOAD_ID = ProtocolStrings.PAYLOAD_PLACE_COMPONENT;

    public static final PayloadType<PlaceComponentPayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, PlaceComponentPayload::new);

    private UUID worldId;
    private String elementTypeId;
    private double x;
    private double y;
    private double angle;

    public PlaceComponentPayload() {
    }

    public PlaceComponentPayload(UUID worldId, String elementTypeId, double x, double y, double angle) {
        this.worldId = worldId;
        this.elementTypeId = elementTypeId;
        this.x = x;
        this.y = y;
        this.angle = angle;
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
    public double getAngle() { return angle; }

    @Override
    public void save(CompoundTag tag) {
        Payload.super.save(tag);
        TagUtil.putUUID(tag, ProtocolStrings.TAG_WORLD_ID, worldId);
        tag.putString(ProtocolStrings.TAG_ELEMENT_TYPE_ID, elementTypeId == null ? "" : elementTypeId);
        tag.putDouble(ProtocolStrings.TAG_X, x);
        tag.putDouble(ProtocolStrings.TAG_Y, y);
        tag.putDouble(ProtocolStrings.TAG_ANGLE, angle);
    }

    @Override
    public void load(CompoundTag tag) {
        Payload.super.load(tag);
        worldId = Payload.requireUUID(tag, ProtocolStrings.TAG_WORLD_ID);
        elementTypeId = tag.getString(ProtocolStrings.TAG_ELEMENT_TYPE_ID);
        if (elementTypeId == null || elementTypeId.isEmpty()) {
            throw new IllegalArgumentException("Missing '" + ProtocolStrings.TAG_ELEMENT_TYPE_ID + "'");
        }
        x = tag.getDouble(ProtocolStrings.TAG_X);
        y = tag.getDouble(ProtocolStrings.TAG_Y);
        angle = tag.getDouble(ProtocolStrings.TAG_ANGLE);
    }
}
