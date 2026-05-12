package com.minecart.client.network;

import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Inbound Netty handler that receives decoded {@link Payload}s on the client side. Marshals execution onto the
 * provided {@code mainThread} {@link Executor} (typically the LibGDX render thread via {@code Gdx.app::postRunnable})
 * before invoking the per-type {@link PayloadHandler}.
 * <p>
 * Rejects (closes the channel) any payload whose {@link Payload#getDestination()} is not {@link Payload.Destination#CLIENT}
 * — sending a server-bound payload to the client is a protocol violation.
 * <p>
 * One instance per {@link ClientConnection}; not safe for sharing across channels because handler registration is
 * mutable per-instance.
 */
public class ClientPayloadDispatcher extends SimpleChannelInboundHandler<Payload> {

    private final Executor mainThread;
    private final Map<Class<? extends Payload>, PayloadHandler<?>> handlers = new HashMap<>();

    /**
     * @param mainThread executor that runs handler invocations on the desired UI thread
     *                   (e.g. {@code Gdx.app::postRunnable} for LibGDX)
     */
    public ClientPayloadDispatcher(Executor mainThread) {
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
    }

    /**
     * Registers the receive-side handler for one payload kind. Returns {@code this} for chaining.
     */
    public <P extends Payload> ClientPayloadDispatcher register(Class<P> type, PayloadHandler<P> handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        handlers.put(type, handler);
        return this;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Payload msg) {
        if (msg.getDestination() != Payload.Destination.CLIENT) {
            ctx.close();
            return;
        }
        mainThread.execute(() -> dispatch(msg));
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
