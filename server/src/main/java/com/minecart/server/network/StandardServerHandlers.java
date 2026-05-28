package com.minecart.server.network;

import com.minecart.logic.ServerLevel;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.client.ActionPayload;
import com.minecart.protocol.payload.client.CombineCascadePayload;
import com.minecart.protocol.payload.client.ConnectEdgePayload;
import com.minecart.protocol.payload.client.CreateWorldPayload;
import com.minecart.protocol.payload.client.DeleteElementPayload;
import com.minecart.protocol.payload.client.DeleteWorldPayload;
import com.minecart.protocol.payload.client.DragBeginPayload;
import com.minecart.protocol.payload.client.DragEndPayload;
import com.minecart.protocol.payload.client.EdgeEndpointChangePayload;
import com.minecart.protocol.payload.client.ElementInfoUpdatePayload;
import com.minecart.protocol.payload.client.MoveElementPayload;
import com.minecart.protocol.payload.client.PlaceComponentPayload;
import com.minecart.protocol.payload.client.PlaceNodePayload;
import com.minecart.protocol.payload.client.RenameWorldPayload;
import com.minecart.protocol.payload.client.ReplaceComponentNodePayload;
import com.minecart.protocol.payload.client.RotateElementPayload;
import com.minecart.protocol.payload.server.CircuitLifecyclePayload;
import com.minecart.protocol.payload.server.WorldLifecyclePayload;
import com.minecart.server.handler.ActionPayloadHandler;
import com.minecart.server.handler.CircuitLifecycleHandler;
import com.minecart.server.handler.CombineCascadeHandler;
import com.minecart.server.handler.ConnectEdgeHandler;
import com.minecart.server.handler.CreateWorldHandler;
import com.minecart.server.handler.DeleteElementHandler;
import com.minecart.server.handler.DeleteWorldHandler;
import com.minecart.server.handler.DragBeginHandler;
import com.minecart.server.handler.DragEndHandler;
import com.minecart.server.handler.EdgeEndpointChangeHandler;
import com.minecart.server.handler.ElementInfoUpdateHandler;
import com.minecart.server.handler.MoveElementHandler;
import com.minecart.server.handler.PlaceComponentHandler;
import com.minecart.server.handler.PlaceNodeHandler;
import com.minecart.server.handler.RenameWorldHandler;
import com.minecart.server.handler.ReplaceComponentNodeHandler;
import com.minecart.server.handler.RotateElementHandler;
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
        dispatcher.register(MoveElementPayload.class, new MoveElementHandler(level));
        dispatcher.register(DeleteElementPayload.class, new DeleteElementHandler(level));
        dispatcher.register(EdgeEndpointChangePayload.class, new EdgeEndpointChangeHandler(level));
        dispatcher.register(ReplaceComponentNodePayload.class, new ReplaceComponentNodeHandler(level));
        dispatcher.register(ElementInfoUpdatePayload.class, new ElementInfoUpdateHandler(level));
        dispatcher.register(CombineCascadePayload.class, new CombineCascadeHandler(level));
        dispatcher.register(RotateElementPayload.class, new RotateElementHandler(level));
        dispatcher.register(DragBeginPayload.class, new DragBeginHandler(level));
        dispatcher.register(DragEndPayload.class, new DragEndHandler(level));
        dispatcher.register(CreateWorldPayload.class, new CreateWorldHandler(level, broadcast));
        dispatcher.register(DeleteWorldPayload.class, new DeleteWorldHandler(level, broadcast));
        dispatcher.register(RenameWorldPayload.class, new RenameWorldHandler(level, broadcast));
    }
}
