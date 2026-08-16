package com.minecart.client.network;

import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(ClientPayloadDispatcher.class);

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
            log.warn("Rejecting server-bound payload {} received on the client; closing channel",
                    msg.getClass().getSimpleName());
            ctx.close();
            return;
        }
        mainThread.execute(() -> dispatch(ctx, msg));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dispatch(ChannelHandlerContext ctx, Payload msg) {
        PayloadHandler handler = handlers.get(msg.getClass());
        if (handler == null) {
            return;
        }
        try {
            handler.handle(msg);
        } catch (Throwable t) {
            // The handler runs on the UI thread (not the Netty pipeline), so a thrown exception would
            // never reach exceptionCaught — it would bubble up through the render loop and kill it.
            // Log it and close the connection instead (ctx.close() is safe to call from any thread).
            log.error("Handler for payload {} threw; closing connection",
                    msg.getClass().getSimpleName(), t);
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Client network pipeline error; closing connection", cause);
        ctx.close();
    }
}
