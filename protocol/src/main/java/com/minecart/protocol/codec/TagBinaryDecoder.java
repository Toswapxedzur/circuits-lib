package com.minecart.protocol.codec;

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
 * Decodes length-prefixed frames produced by {@link TagBinaryEncoder} into {@link Tag.BinaryWithContext}
 * via {@link Tag#readBinary(java.io.DataInput)}.
 *
 * <p><strong>Not</strong> {@link io.netty.channel.ChannelHandler.Sharable}: {@link ByteToMessageDecoder}
 * keeps a per-channel cumulation buffer, so a fresh instance is required per pipeline (matching
 * {@link PayloadDecoder}).
 */
public class TagBinaryDecoder extends ByteToMessageDecoder {

    private final int maxFrameLength;

    public TagBinaryDecoder() {
        this(TagBinaryEncoder.DEFAULT_MAX_PAYLOAD_LENGTH);
    }

    /**
     * @param maxFrameLength maximum payload bytes after the length field (must match encoder limit)
     */
    public TagBinaryDecoder(int maxFrameLength) {
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
        byte[] payload = new byte[length];
        in.readBytes(payload);
        try (DataInputStream din = new DataInputStream(new ByteArrayInputStream(payload))) {
            Tag.BinaryWithContext decoded = Tag.readBinary(din);
            out.add(decoded);
        } catch (IOException e) {
            throw new CorruptedFrameException("Failed to parse tag binary", e);
        }
    }
}
