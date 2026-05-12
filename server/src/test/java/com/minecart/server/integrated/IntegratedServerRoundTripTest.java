package com.minecart.server.integrated;

import com.minecart.action.Actions;
import com.minecart.protocol.codec.PayloadDecoder;
import com.minecart.protocol.codec.PayloadEncoder;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.ActionPayload;
import com.minecart.protocol.payload.server.CircuitElementPayload;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalChannel;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check that {@link IntegratedServer}'s in-process LocalChannel pipeline accepts a SERVER-bound payload
 * ({@link ActionPayload}) and routes it onto the server-tick thread via {@link com.minecart.server.network.ServerPayloadDispatcher}.
 * Also verifies that a CLIENT-bound payload ({@link CircuitElementPayload}) sent in the wrong direction is rejected
 * (channel closed by the dispatcher's destination check).
 */
class IntegratedServerRoundTripTest {

    @Test
    void serverBoundPayloadReachesDispatcher() throws Exception {
        IntegratedServer server = new IntegratedServer();
        // Replace the default ActionPayloadHandler with a counting probe so we can assert the payload arrived.
        CountDownLatch arrived = new CountDownLatch(1);
        server.dispatcher().register(ActionPayload.class, (PayloadHandler<ActionPayload>) p -> arrived.countDown());
        server.start();

        EventLoopGroup loop = new DefaultEventLoopGroup(1);
        Channel ch = null;
        try {
            ch = new Bootstrap()
                    .group(loop)
                    .channel(LocalChannel.class)
                    .handler(new ChannelInitializer<>() {
                        @Override protected void initChannel(Channel c) {
                            c.pipeline()
                                    .addLast(new PayloadDecoder())
                                    .addLast(new PayloadEncoder());
                        }
                    })
                    .connect(server.address()).sync().channel();

            ActionPayload action = new ActionPayload(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new Actions.SetVoltageAction(0.0));
            ch.writeAndFlush(action).sync();

            assertTrue(arrived.await(2, TimeUnit.SECONDS),
                    "Server-bound ActionPayload should reach the dispatcher within 2s");
            assertTrue(ch.isActive(), "Channel should still be open after a valid SERVER-bound payload");
        } finally {
            if (ch != null && ch.isActive()) ch.close().sync();
            loop.shutdownGracefully();
            server.stop();
        }
    }

    @Test
    void clientBoundPayloadIsRejected() throws Exception {
        IntegratedServer server = new IntegratedServer();
        server.start();

        EventLoopGroup loop = new DefaultEventLoopGroup(1);
        Channel ch = null;
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Channel> chRef = new AtomicReference<>();
        try {
            ch = new Bootstrap()
                    .group(loop)
                    .channel(LocalChannel.class)
                    .handler(new ChannelInitializer<>() {
                        @Override protected void initChannel(Channel c) {
                            chRef.set(c);
                            c.closeFuture().addListener(f -> closed.countDown());
                            c.pipeline()
                                    .addLast(new PayloadDecoder())
                                    .addLast(new PayloadEncoder())
                                    .addLast(new SimpleChannelInboundHandler<Payload>() {
                                        @Override protected void channelRead0(ChannelHandlerContext ctx, Payload msg) {
                                            // Only inbound payloads from server would arrive here; not expected in this test.
                                        }
                                    });
                        }
                    })
                    .connect(server.address()).sync().channel();

            // CLIENT-bound payload sent to the server: server should close the channel.
            ch.writeAndFlush(new CircuitElementPayload(UUID.randomUUID(), UUID.randomUUID(), java.util.List.of()));

            assertTrue(closed.await(2, TimeUnit.SECONDS), "Server should close channel after rejecting client-bound payload");
            assertEquals(false, chRef.get().isActive(), "Channel should be inactive after server-side close");
        } finally {
            if (ch != null && ch.isActive()) ch.close().sync();
            loop.shutdownGracefully();
            server.stop();
        }
    }
}
