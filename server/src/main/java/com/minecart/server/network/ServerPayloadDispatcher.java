package com.minecart.server.network;

import com.minecart.logic.ServerLevel;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Inbound Netty handler that receives decoded {@link Payload}s on the server side. Marshals execution onto the
 * server-tick thread by calling {@link ServerLevel#submit(Runnable)}; the level's action queue is drained at the
 * start of each {@link ServerLevel#tick()}.
 * <p>
 * Rejects (closes the channel) any payload whose {@link Payload#getDestination()} is not {@link Payload.Destination#SERVER}
 * — sending a client-bound payload to the server is a protocol violation.
 * <p>
 * One instance per {@link com.minecart.server.integrated.IntegratedServer} or dedicated server bootstrap; safe to share
 * across all child channels of one server because the routing table is read-only after startup.
 */
public class ServerPayloadDispatcher extends SimpleChannelInboundHandler<Payload> {

    private final ServerLevel level;
    private final Map<Class<? extends Payload>, PayloadHandler<?>> handlers = new HashMap<>();

    public ServerPayloadDispatcher(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    /**
     * Registers the receive-side handler for one payload kind. Returns {@code this} for chaining.
     */
    public <P extends Payload> ServerPayloadDispatcher register(Class<P> type, PayloadHandler<P> handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        handlers.put(type, handler);
        return this;
    }

    public ServerLevel getLevel() {
        return level;
    }

    @Override
    public boolean isSharable() {
        return true;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Payload msg) {
        if (msg.getDestination() != Payload.Destination.SERVER) {
            ctx.close();
            return;
        }
        level.submit(() -> dispatch(msg));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dispatch(Payload msg) {
        PayloadHandler handler = handlers.get(msg.getClass());
        if (handler == null) {
            return;
        }
        handler.handle(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
