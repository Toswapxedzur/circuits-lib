package com.minecart.protocol.codec;

import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadRegistry;
import com.minecart.protocol.payload.PayloadType;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.Tag;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.List;

/**
 * Inbound Netty handler: reads a length-prefixed binary tag frame from the channel, looks up the matching
 * {@link PayloadType} in {@link PayloadRegistry}, instantiates a fresh {@link Payload}, and forwards it down the
 * pipeline. Pair with {@link PayloadEncoder} on the send side. Handles partial frames safely (resets reader index
 * until the full payload has arrived).
 * <p>
 * <strong>Not</strong> {@link io.netty.channel.ChannelHandler.Sharable}: {@link ByteToMessageDecoder} keeps a
 * per-channel cumulation buffer, so a fresh instance is required per pipeline.
 */
public class PayloadDecoder extends ByteToMessageDecoder {

    private final int maxFrameLength;

    public PayloadDecoder() {
        this(TagBinaryEncoder.DEFAULT_MAX_PAYLOAD_LENGTH);
    }

    public PayloadDecoder(int maxFrameLength) {
        if (maxFrameLength <= 0) {
            throw new IllegalArgumentException("maxFrameLength must be positive");
        }
        this.maxFrameLength = maxFrameLength;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < 4) {
            return;
        }
        in.markReaderIndex();
        int length = in.readInt();
        if (length < 0) {
            throw new CorruptedFrameException("negative frame length: " + length);
        }
        if (length > maxFrameLength) {
            throw new CorruptedFrameException("frame length " + length + " exceeds max " + maxFrameLength);
        }
        if (in.readableBytes() < length) {
            in.resetReaderIndex();
            return;
        }
        byte[] frame = new byte[length];
        in.readBytes(frame);
        try (DataInputStream din = new DataInputStream(new ByteArrayInputStream(frame))) {
            Tag.BinaryWithContext decoded = Tag.readBinary(din);
            Tag root = decoded.root();
            if (!(root instanceof CompoundTag compound)) {
                throw new CorruptedFrameException("Expected CompoundTag root, got " + root.getClass().getName());
            }
            String id = Payload.peekPayloadId(compound);
            if (id == null || id.isEmpty()) {
                throw new CorruptedFrameException("Missing or empty payload id in tag");
            }
            PayloadType<?> type = PayloadRegistry.getType(id);
            Payload payload = type.create();
            payload.load(compound);
            out.add(payload);
        } catch (IOException e) {
            throw new CorruptedFrameException("Failed to parse payload tag", e);
        }
    }
}
