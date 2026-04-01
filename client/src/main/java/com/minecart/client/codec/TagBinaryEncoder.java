package com.minecart.client.codec;

import com.minecart.serialization.tag.Tag;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Encodes a {@link Tag} to bytes using {@link Tag#writeBinary(java.io.DataOutput, Tag)}, prefixed with a
 * 4-byte big-endian length so {@link TagBinaryDecoder} can split messages on a TCP stream.
 */
@ChannelHandler.Sharable
public class TagBinaryEncoder extends MessageToByteEncoder<Tag> {

    @Override
    protected void encode(ChannelHandlerContext ctx, Tag msg, ByteBuf out) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream dataOut = new DataOutputStream(buffer)) {
            Tag.writeBinary(dataOut, msg);
        }
        byte[] payload = buffer.toByteArray();
        if (payload.length > maxPayloadLength()) {
            throw new IOException("Tag binary size " + payload.length + " exceeds limit " + maxPayloadLength());
        }
        out.writeInt(payload.length);
        out.writeBytes(payload);
    }

    /** Override to cap encoded size (default {@value #DEFAULT_MAX_PAYLOAD_LENGTH}). */
    protected int maxPayloadLength() {
        return DEFAULT_MAX_PAYLOAD_LENGTH;
    }

    public static final int DEFAULT_MAX_PAYLOAD_LENGTH = 8 * 1024 * 1024;
}
