package com.minecart.protocol.payload;

import com.minecart.protocol.payload.client.ActionPayload;
import com.minecart.protocol.payload.client.ConnectEdgePayload;
import com.minecart.protocol.payload.client.CreateWorldPayload;
import com.minecart.protocol.payload.client.DeleteWorldPayload;
import com.minecart.protocol.payload.client.PlaceComponentPayload;
import com.minecart.protocol.payload.client.PlaceNodePayload;
import com.minecart.protocol.payload.client.RenameWorldPayload;
import com.minecart.protocol.payload.server.CircuitElementPayload;
import com.minecart.protocol.payload.server.CircuitLifecyclePayload;
import com.minecart.protocol.payload.server.CircuitSnapshotPayload;
import com.minecart.protocol.payload.server.WorldLifecyclePayload;

/**
 * Aggregates handles for all {@link Payload} kinds (registration happens in each payload class, e.g. {@link ActionPayload#TYPE}).
 * Reference this class during startup if you want a single place to touch every kind — same role as
 * {@link com.minecart.registry.AllComponents}.
 */
public final class AllPayloads {
    public static final PayloadType<ActionPayload> ACTION = ActionPayload.TYPE;
    public static final PayloadType<CircuitElementPayload> CIRCUIT_ELEMENT = CircuitElementPayload.TYPE;
    public static final PayloadType<CircuitSnapshotPayload> CIRCUIT_SNAPSHOT = CircuitSnapshotPayload.TYPE;
    public static final PayloadType<CircuitLifecyclePayload> CIRCUIT_LIFECYCLE = CircuitLifecyclePayload.TYPE;
    public static final PayloadType<WorldLifecyclePayload> WORLD_LIFECYCLE = WorldLifecyclePayload.TYPE;
    public static final PayloadType<PlaceNodePayload> PLACE_NODE = PlaceNodePayload.TYPE;
    public static final PayloadType<PlaceComponentPayload> PLACE_COMPONENT = PlaceComponentPayload.TYPE;
    public static final PayloadType<ConnectEdgePayload> CONNECT_EDGE = ConnectEdgePayload.TYPE;
    public static final PayloadType<CreateWorldPayload> CREATE_WORLD = CreateWorldPayload.TYPE;
    public static final PayloadType<DeleteWorldPayload> DELETE_WORLD = DeleteWorldPayload.TYPE;
    public static final PayloadType<RenameWorldPayload> RENAME_WORLD = RenameWorldPayload.TYPE;

    private AllPayloads() {}
}
