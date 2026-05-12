package com.minecart.protocol.codec;

import com.minecart.protocol.payload.Payload;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Outbound Netty handler: writes a {@link Payload} to the channel using {@link Payload#encode(ByteBuf)} (4-byte length
 * prefix + binary tag). Pair with {@link PayloadDecoder} on the receive side. Sharable across channels.
 */
@ChannelHandler.Sharable
public class PayloadEncoder extends MessageToByteEncoder<Payload> {

    @Override
    protected void encode(ChannelHandlerContext ctx, Payload msg, ByteBuf out) {
        msg.encode(out);
    }
}
