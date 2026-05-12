package com.minecart.server.network;

import com.minecart.logic.ServerLevel;
import com.minecart.protocol.payload.client.ActionPayload;
import com.minecart.protocol.payload.server.CircuitLifecyclePayload;
import com.minecart.protocol.payload.server.WorldLifecyclePayload;
import com.minecart.server.handler.ActionPayloadHandler;
import com.minecart.server.handler.CircuitLifecycleHandler;
import com.minecart.server.handler.WorldLifecycleHandler;

/**
 * One-stop registration of the built-in server-side {@link com.minecart.protocol.payload.PayloadHandler}s on a
 * {@link ServerPayloadDispatcher}. Call from {@link com.minecart.server.integrated.IntegratedServer} or
 * {@link com.minecart.server.dedicated.DedicatedServerMain} before binding the channel.
 */
public final class StandardServerHandlers {

    private StandardServerHandlers() {}

    public static void register(ServerPayloadDispatcher dispatcher, ServerLevel level) {
        dispatcher.register(ActionPayload.class, new ActionPayloadHandler(level));
        dispatcher.register(CircuitLifecyclePayload.class, new CircuitLifecycleHandler(level));
        dispatcher.register(WorldLifecyclePayload.class, new WorldLifecycleHandler(level));
    }
}
