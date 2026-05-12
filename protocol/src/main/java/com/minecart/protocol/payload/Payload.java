package com.minecart.protocol.payload;

import com.minecart.protocol.codec.TagBinaryEncoder;
import com.minecart.protocol.misc.ProtocolStrings;
import com.minecart.serialization.TagSerializable;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.Tag;
import io.netty.buffer.ByteBuf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Data-only payload: identity and tag fields via {@link TagSerializable}. No receive-side behavior here;
 * after decode, a {@link PayloadHandler} performs routing and side effects ({@code PayloadHandler<?>} or a concrete
 * {@code PayloadHandler<MyPayload>}).
 * <p>
 * {@link #encode(ByteBuf)} / {@link #decode(ByteBuf)} default to the same length-prefixed binary tag format as
 * {@link com.minecart.protocol.codec.TagBinaryEncoder} / {@link com.minecart.protocol.codec.TagBinaryDecoder}; override
 * for a more compact custom wire layout when needed.
 */
public interface Payload extends TagSerializable {

    /** Root compound key for type dispatch (written with the payload body by typical {@link Payload} implementations). */
    String TAG_PAYLOAD_ID = ProtocolStrings.PAYLOAD_ENVELOPE_ID;

    /**
     * Stable wire id for this payload kind (registry key). Must match the id used with {@link PayloadRegistry}.
     */
    String getPayloadId();

    /**
     * Writes {@link #TAG_PAYLOAD_ID} and this payload's {@link #getPayloadId()}. Concrete {@link Payload} types must
     * call {@code Payload.super.save(tag)} first, then write their own fields.
     */
    @Override
    default void save(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        tag.putString(TAG_PAYLOAD_ID, getPayloadId());
    }

    /**
     * Verifies {@link #TAG_PAYLOAD_ID} matches this instance's {@link #getPayloadId()}. Concrete types must call
     * {@code Payload.super.load(tag)} first, then read their own fields.
     */
    @Override
    default void load(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        String id = tag.getString(TAG_PAYLOAD_ID);
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Missing " + TAG_PAYLOAD_ID);
        }
        if (!getPayloadId().equals(id)) {
            throw new IllegalArgumentException("Payload id mismatch: expected " + getPayloadId() + ", got " + id);
        }
    }

    /**
     * Serializes a payload to a new root compound (includes type id and subtype fields).
     */
    static CompoundTag serialize(Payload payload) {
        Objects.requireNonNull(payload, "payload");
        CompoundTag tag = new CompoundTag();
        payload.save(tag);
        return tag;
    }

    /**
     * Deserializes by {@link #TAG_PAYLOAD_ID} via {@link PayloadRegistry}, then {@link #load(CompoundTag)}.
     */
    static Payload deserialize(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
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
     */
    static <T extends Payload> T deserialize(CompoundTag tag, Class<T> type) {
        Payload p = deserialize(tag);
        if (!type.isInstance(p)) {
            throw new IllegalArgumentException(
                    "Expected payload type " + type.getName() + " but got " + p.getClass().getName());
        }
        return type.cast(p);
    }

    /** Reads {@link #TAG_PAYLOAD_ID} without instantiating a payload (for dispatch). */
    static String peekPayloadId(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        return tag.getString(TAG_PAYLOAD_ID);
    }

    /**
     * Writes this payload to {@code out} using the default wire format: big-endian 4-byte length, then bytes from
     * {@link Tag#writeBinary(java.io.DataOutput, Tag)} on the tag produced by {@link #save(CompoundTag)}.
     */
    default void encode(ByteBuf out) {
        Objects.requireNonNull(out, "out");
        CompoundTag tag = new CompoundTag();
        save(tag);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream dataOut = new DataOutputStream(buffer)) {
                Tag.writeBinary(dataOut, tag);
            }
            byte[] payload = buffer.toByteArray();
            if (payload.length > TagBinaryEncoder.DEFAULT_MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException(
                        "Encoded tag size " + payload.length + " exceeds limit " + TagBinaryEncoder.DEFAULT_MAX_PAYLOAD_LENGTH);
            }
            out.writeInt(payload.length);
            out.writeBytes(payload);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads the default wire format from {@code in} (length prefix + tag binary) and {@link #load(CompoundTag)}s
     * the root compound. Consumes exactly the framed bytes from the buffer.
     */
    default void decode(ByteBuf in) {
        Objects.requireNonNull(in, "in");
        if (in.readableBytes() < 4) {
            throw new IllegalArgumentException("ByteBuf too short for length prefix");
        }
        int length = in.readInt();
        if (length < 0) {
            throw new IllegalArgumentException("negative frame length: " + length);
        }
        if (length > TagBinaryEncoder.DEFAULT_MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException(
                    "frame length " + length + " exceeds max " + TagBinaryEncoder.DEFAULT_MAX_PAYLOAD_LENGTH);
        }
        if (in.readableBytes() < length) {
            throw new IllegalArgumentException("ByteBuf too short: need " + length + " payload bytes");
        }
        byte[] payload = new byte[length];
        in.readBytes(payload);
        try (DataInputStream din = new DataInputStream(new ByteArrayInputStream(payload))) {
            Tag.BinaryWithContext decoded = Tag.readBinary(din);
            Tag root = decoded.root();
            if (!(root instanceof CompoundTag compound)) {
                throw new IllegalArgumentException("Expected CompoundTag root, got " + root.getClass().getName());
            }
            load(compound);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Destination getDestination();

    enum Destination {
        SERVER,
        CLIENT
    }
}
