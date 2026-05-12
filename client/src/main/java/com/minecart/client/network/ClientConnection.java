package com.minecart.client.network;

import com.minecart.protocol.codec.PayloadDecoder;
import com.minecart.protocol.codec.PayloadEncoder;
import com.minecart.protocol.payload.Payload;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.util.Objects;

/**
 * Single client-side network connection. Hosts the Netty {@link Channel} and {@link EventLoopGroup}, and exposes
 * {@link #connectIntegrated(LocalAddress)} (in-process LocalChannel for singleplayer) and
 * {@link #connectRemote(String, int)} (TCP NioSocketChannel for multiplayer). The pipeline is identical in both
 * cases: {@link PayloadDecoder} ↔ {@link PayloadEncoder} ↔ provided {@link ClientPayloadDispatcher}.
 * <p>
 * Lifecycle: construct → {@code connect…} → {@link #send(Payload)} as needed → {@link #close()}.
 * Not reusable: after {@code close()}, build a new instance for the next session.
 */
public class ClientConnection {

    private final ClientPayloadDispatcher dispatcher;

    private EventLoopGroup loop;
    private Channel channel;

    public ClientConnection(ClientPayloadDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    public ClientPayloadDispatcher getDispatcher() {
        return dispatcher;
    }

    /**
     * In-process loopback to an integrated server bound at {@code address}. Blocks until the channel is established.
     */
    public synchronized ChannelFuture connectIntegrated(LocalAddress address) throws InterruptedException {
        Objects.requireNonNull(address, "address");
        ensureNotConnected();
        loop = new DefaultEventLoopGroup(1, new DefaultThreadFactory("client-net-local", true));
        Bootstrap b = new Bootstrap()
                .group(loop)
                .channel(LocalChannel.class)
                .handler(buildInitializer());
        ChannelFuture f = b.connect(address).sync();
        channel = f.channel();
        return f;
    }

    /**
     * TCP connection to a remote dedicated server. Blocks until the channel is established.
     */
    public synchronized ChannelFuture connectRemote(String host, int port) throws InterruptedException {
        Objects.requireNonNull(host, "host");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        ensureNotConnected();
        loop = new NioEventLoopGroup(1, new DefaultThreadFactory("client-net-nio", true));
        Bootstrap b = new Bootstrap()
                .group(loop)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(buildInitializer());
        ChannelFuture f = b.connect(host, port).sync();
        channel = f.channel();
        return f;
    }

    /**
     * Writes a payload to the channel. Returns the {@link ChannelFuture} so callers can chain or await flush.
     */
    public ChannelFuture send(Payload payload) {
        Objects.requireNonNull(payload, "payload");
        if (channel == null) {
            throw new IllegalStateException("Not connected");
        }
        return channel.writeAndFlush(payload);
    }

    public boolean isConnected() {
        return channel != null && channel.isActive();
    }

    /**
     * Closes the channel and shuts down the event loop. Safe to call multiple times.
     */
    public synchronized void close() {
        try {
            if (channel != null) {
                channel.close().syncUninterruptibly();
            }
        } finally {
            channel = null;
            if (loop != null) {
                loop.shutdownGracefully();
                loop = null;
            }
        }
    }

    private ChannelInitializer<Channel> buildInitializer() {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel ch) {
                ch.pipeline()
                        .addLast("decoder", new PayloadDecoder())
                        .addLast("encoder", new PayloadEncoder())
                        .addLast("dispatcher", dispatcher);
            }
        };
    }

    private void ensureNotConnected() {
        if (channel != null) {
            throw new IllegalStateException("Already connected; create a new ClientConnection per session");
        }
    }
}
