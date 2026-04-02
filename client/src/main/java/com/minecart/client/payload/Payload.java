package com.minecart.client.payload;

import com.minecart.serialization.TagSerializable;
import com.minecart.serialization.tag.CompoundTag;

/**
 * Ancestor for tag-serialized payloads carrying a stable {@linkplain #getPayloadId()} so readers can
 * discriminate types (e.g. network frames) before fully decoding. Register kinds with {@link PayloadRegistry}.
 * Use {@link #serialize(Payload)} / {@link #deserialize(CompoundTag)} for round-trips, or {@link #save}/{@link #load}
 * on an existing {@link CompoundTag}.
 */
public abstract class Payload implements TagSerializable {

    public static final String TAG_PAYLOAD_ID = "payload_id";

    /**
     * Unique id for this payload kind (e.g. {@code "minecart.action_payload"}). Written under {@link #TAG_PAYLOAD_ID}.
     */
    public abstract String getPayloadId();

    @Override
    public final void save(CompoundTag tag) {
        tag.putString(TAG_PAYLOAD_ID, getPayloadId());
        savePayload(tag);
    }

    @Override
    public final void load(CompoundTag tag) {
        verifyPayloadId(tag);
        loadPayload(tag);
    }

    protected abstract void savePayload(CompoundTag tag);

    protected abstract void loadPayload(CompoundTag tag);

    /**
     * Writes {@code payload} to a new root tag (includes {@link #TAG_PAYLOAD_ID} and subtype-specific entries).
     */
    public static CompoundTag serialize(Payload payload) {
        CompoundTag tag = new CompoundTag();
        payload.save(tag);
        return tag;
    }

    /**
     * Decodes a root tag by looking up {@link #TAG_PAYLOAD_ID} in {@link PayloadRegistry}, then {@link #load(CompoundTag)}.
     */
    public static Payload deserialize(CompoundTag tag) {
        String id = peekPayloadId(tag);
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("Missing or empty " + TAG_PAYLOAD_ID);
        }
        PayloadType<?> payloadType = PayloadRegistry.getType(id);
        Payload payload = payloadType.create();
        payload.load(tag);
        return payload;
    }

    /**
     * Same as {@link #deserialize(CompoundTag)} but narrows to {@code type}.
     *
     * @throws IllegalArgumentException if the decoded payload is not an instance of {@code type}
     */
    public static <T extends Payload> T deserialize(CompoundTag tag, Class<T> type) {
        Payload p = deserialize(tag);
        if (!type.isInstance(p)) {
            throw new IllegalArgumentException(
                    "Expected payload type " + type.getName() + " but got " + p.getClass().getName());
        }
        return type.cast(p);
    }

    /** Reads {@link #TAG_PAYLOAD_ID} without instantiating a payload (for dispatch). */
    public static String peekPayloadId(CompoundTag tag) {
        return tag.getString(TAG_PAYLOAD_ID);
    }

    protected void verifyPayloadId(CompoundTag tag) {
        String id = tag.getString(TAG_PAYLOAD_ID);
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Missing " + TAG_PAYLOAD_ID);
        }
        if (!getPayloadId().equals(id)) {
            throw new IllegalArgumentException("Payload id mismatch: expected " + getPayloadId() + ", got " + id);
        }
    }
}
