package com.minecart.client.payload;

import com.minecart.client.codec.TagBinaryEncoder;
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
 * {@link com.minecart.client.codec.TagBinaryEncoder} / {@link com.minecart.client.codec.TagBinaryDecoder}; override
 * for a more compact custom wire layout when needed.
 */
public interface Payload extends TagSerializable {

    /** Root compound key for type dispatch (written with the payload body by typical {@link Payload} implementations). */
    String TAG_PAYLOAD_ID = "payload_id";

    /**
     * Stable wire id for this payload kind (registry key). Must match the id used with {@link PayloadRegistry}.
     */
    String getPayloadId();

    /**
     * Writes {@link #TAG_PAYLOAD_ID} and {@link #getPayloadId()} before subtype fields (typical root tag shape).
     */
    static void writeEnvelope(CompoundTag tag, Payload payload) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(payload, "payload");
        tag.putString(TAG_PAYLOAD_ID, payload.getPayloadId());
    }

    /**
     * Verifies the root tag was written for the expected payload kind.
     */
    static void verifyEnvelope(CompoundTag tag, String expectedPayloadId) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(expectedPayloadId, "expectedPayloadId");
        String id = tag.getString(TAG_PAYLOAD_ID);
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Missing " + TAG_PAYLOAD_ID);
        }
        if (!expectedPayloadId.equals(id)) {
            throw new IllegalArgumentException("Payload id mismatch: expected " + expectedPayloadId + ", got " + id);
        }
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
