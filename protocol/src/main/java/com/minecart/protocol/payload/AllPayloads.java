package com.minecart.protocol.payload;

import com.minecart.protocol.payload.client.ActionPayload;
import com.minecart.protocol.payload.client.CombineCascadePayload;
import com.minecart.protocol.payload.client.ConnectEdgePayload;
import com.minecart.protocol.payload.client.CreateWorldPayload;
import com.minecart.protocol.payload.client.DeleteElementPayload;
import com.minecart.protocol.payload.client.DeleteWorldPayload;
import com.minecart.protocol.payload.client.EdgeEndpointChangePayload;
import com.minecart.protocol.payload.client.ElementInfoUpdatePayload;
import com.minecart.protocol.payload.client.MoveElementPayload;
import com.minecart.protocol.payload.client.PlaceComponentPayload;
import com.minecart.protocol.payload.client.PlaceNodePayload;
import com.minecart.protocol.payload.client.RenameWorldPayload;
import com.minecart.protocol.payload.client.DragBeginPayload;
import com.minecart.protocol.payload.client.DragEndPayload;
import com.minecart.protocol.payload.client.ReplaceComponentNodePayload;
import com.minecart.protocol.payload.client.RotateElementPayload;
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
    public static final PayloadType<MoveElementPayload> MOVE_ELEMENT = MoveElementPayload.TYPE;
    public static final PayloadType<DeleteElementPayload> DELETE_ELEMENT = DeleteElementPayload.TYPE;
    public static final PayloadType<EdgeEndpointChangePayload> EDGE_ENDPOINT_CHANGE = EdgeEndpointChangePayload.TYPE;
    public static final PayloadType<ReplaceComponentNodePayload> REPLACE_COMPONENT_NODE = ReplaceComponentNodePayload.TYPE;
    public static final PayloadType<ElementInfoUpdatePayload> ELEMENT_INFO_UPDATE = ElementInfoUpdatePayload.TYPE;
    public static final PayloadType<CombineCascadePayload> COMBINE_CASCADE = CombineCascadePayload.TYPE;
    public static final PayloadType<RotateElementPayload> ROTATE_ELEMENT = RotateElementPayload.TYPE;
    public static final PayloadType<DragBeginPayload> DRAG_BEGIN = DragBeginPayload.TYPE;
    public static final PayloadType<DragEndPayload> DRAG_END = DragEndPayload.TYPE;

    private AllPayloads() {}
}
