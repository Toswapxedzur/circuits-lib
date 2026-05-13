package com.minecart.server.network;

import com.minecart.logic.ServerLevel;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.client.ActionPayload;
import com.minecart.protocol.payload.client.ConnectEdgePayload;
import com.minecart.protocol.payload.client.CreateWorldPayload;
import com.minecart.protocol.payload.client.DeleteWorldPayload;
import com.minecart.protocol.payload.client.PlaceComponentPayload;
import com.minecart.protocol.payload.client.PlaceNodePayload;
import com.minecart.protocol.payload.client.RenameWorldPayload;
import com.minecart.protocol.payload.server.CircuitLifecyclePayload;
import com.minecart.protocol.payload.server.WorldLifecyclePayload;
import com.minecart.server.handler.ActionPayloadHandler;
import com.minecart.server.handler.CircuitLifecycleHandler;
import com.minecart.server.handler.ConnectEdgeHandler;
import com.minecart.server.handler.CreateWorldHandler;
import com.minecart.server.handler.DeleteWorldHandler;
import com.minecart.server.handler.PlaceComponentHandler;
import com.minecart.server.handler.PlaceNodeHandler;
import com.minecart.server.handler.RenameWorldHandler;
import com.minecart.server.handler.WorldLifecycleHandler;

import java.util.function.Consumer;

/**
 * One-stop registration of the built-in server-side {@link com.minecart.protocol.payload.PayloadHandler}s on a
 * {@link ServerPayloadDispatcher}. Call from {@link com.minecart.server.integrated.IntegratedServer} or
 * {@link com.minecart.server.dedicated.DedicatedServerMain} before binding the channel.
 */
public final class StandardServerHandlers {

    private StandardServerHandlers() {}

    public static void register(ServerPayloadDispatcher dispatcher, ServerLevel level) {
        register(dispatcher, level, p -> {});
    }

    /**
     * Registers handlers that need to broadcast back to clients (world create/delete/rename) using
     * {@code broadcast}. Sources without a broadcast sink (e.g. dedicated server tooling that doesn't yet
     * track channels) can pass a no-op via {@link #register(ServerPayloadDispatcher, ServerLevel)}.
     */
    public static void register(
            ServerPayloadDispatcher dispatcher,
            ServerLevel level,
            Consumer<Payload> broadcast) {
        dispatcher.register(ActionPayload.class, new ActionPayloadHandler(level));
        dispatcher.register(CircuitLifecyclePayload.class, new CircuitLifecycleHandler(level));
        dispatcher.register(WorldLifecyclePayload.class, new WorldLifecycleHandler(level));
        dispatcher.register(PlaceNodePayload.class, new PlaceNodeHandler(level));
        dispatcher.register(PlaceComponentPayload.class, new PlaceComponentHandler(level));
        dispatcher.register(ConnectEdgePayload.class, new ConnectEdgeHandler(level));
        dispatcher.register(CreateWorldPayload.class, new CreateWorldHandler(level, broadcast));
        dispatcher.register(DeleteWorldPayload.class, new DeleteWorldHandler(level, broadcast));
        dispatcher.register(RenameWorldPayload.class, new RenameWorldHandler(level, broadcast));
    }
}
