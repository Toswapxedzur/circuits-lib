package com.minecart.protocol.misc;

/**
 * Stable payload ids and tag keys used on the wire. Any code that reads or writes the network format
 * (clients, servers, third-party tooling) reuses these constants so the protocol stays in sync.
 */
public final class ProtocolStrings {

    private ProtocolStrings() {
    }

    public static final String PAYLOAD_ACTION = "minecart.action_payload";
    /** Wire id unchanged from legacy {@code minecart.circuit_topology_payload}. */
    public static final String PAYLOAD_CIRCUIT_ELEMENT = "minecart.circuit_topology_payload";
    public static final String PAYLOAD_CIRCUIT_SNAPSHOT = "minecart.circuit_snapshot_payload";
    public static final String PAYLOAD_CIRCUIT_LIFECYCLE = "minecart.circuit_lifecycle_payload";
    public static final String PAYLOAD_WORLD_LIFECYCLE = "minecart.world_lifecycle_payload";
    public static final String PAYLOAD_PLACE_NODE = "minecart.place_node_payload";
    public static final String PAYLOAD_PLACE_COMPONENT = "minecart.place_component_payload";
    public static final String PAYLOAD_CONNECT_EDGE = "minecart.connect_edge_payload";
    public static final String PAYLOAD_RENAME_WORLD = "minecart.rename_world_payload";
    public static final String PAYLOAD_CREATE_WORLD = "minecart.create_world_payload";
    public static final String PAYLOAD_DELETE_WORLD = "minecart.delete_world_payload";
    public static final String PAYLOAD_MOVE_ELEMENT = "minecart.move_element_payload";
    public static final String PAYLOAD_DELETE_ELEMENT = "minecart.delete_element_payload";
    public static final String PAYLOAD_EDGE_ENDPOINT_CHANGE = "minecart.edge_endpoint_change_payload";
    public static final String PAYLOAD_REPLACE_COMPONENT_NODE = "minecart.replace_component_node_payload";
    public static final String PAYLOAD_ELEMENT_INFO_UPDATE = "minecart.element_info_update_payload";
    public static final String PAYLOAD_COMBINE_CASCADE = "minecart.combine_cascade_payload";
    public static final String PAYLOAD_ROTATE_ELEMENT = "minecart.rotate_element_payload";

    public static final String PAYLOAD_ENVELOPE_ID = "payload_id";

    public static final String TAG_WORLD_ID = "world_id";
    public static final String TAG_CIRCUIT_ID = "circuit_id";
    /**
     * Optional on a {@link com.minecart.protocol.payload.server.CircuitElementChange CHANGE} op: id of the
     * circuit the element was last in. When present, the client moves the element from this circuit into
     * the payload's destination circuit before applying any sync data.
     */
    public static final String TAG_SOURCE_CIRCUIT_ID = "source_circuit_id";
    public static final String TAG_ELEMENT_ID = "element_id";
    public static final String TAG_ACTION = "action";
    public static final String TAG_CHANGES = "changes";
    public static final String TAG_KIND = "kind";
    /**
     * {@link com.minecart.registry.CircuitElementType} id string (same key as
     * {@link com.minecart.misc.CoreStrings#ELEMENT_TYPE} in serialized element tags). Canonical
     * name for the wire key {@code "element_type_id"}, shared by placement payloads and
     * {@link com.minecart.protocol.payload.server.CircuitElementChange}.
     */
    public static final String TAG_ELEMENT_TYPE_ID = "element_type_id";
    public static final String TAG_X = "x";
    public static final String TAG_Y = "y";
    /** Optional MoveElementPayload body-local anchor for component drags. */
    public static final String TAG_ANCHOR_LOCAL_X = "anchor_local_x";
    public static final String TAG_ANCHOR_LOCAL_Y = "anchor_local_y";
    public static final String TAG_ANGLE = "angle";
    public static final String TAG_START_NODE_ID = "start_node_id";
    public static final String TAG_END_NODE_ID = "end_node_id";
    public static final String TAG_WORLD_NAME = "world_name";
    /** Combine payload: id of the {@link com.minecart.logic.CircuitComponent} hosting the port being replaced. */
    public static final String TAG_COMPONENT_ID = "component_id";
    /** Combine payload: id of the existing port {@link com.minecart.logic.CircuitNode} that is being absorbed. */
    public static final String TAG_OLD_NODE_ID = "old_node_id";
    /** Combine payload: id of the new {@link com.minecart.logic.CircuitNode} taking over the port slot. */
    public static final String TAG_NEW_NODE_ID = "new_node_id";
    /** Edge-endpoint-change payload: id of the {@link com.minecart.logic.CircuitEdge} being repointed. */
    public static final String TAG_EDGE_ID = "edge_id";
    /** Edge-endpoint-change payload: id of the new {@code start} node. */
    public static final String TAG_NEW_START_NODE_ID = "new_start_node_id";
    /** Edge-endpoint-change payload: id of the new {@code end} node. */
    public static final String TAG_NEW_END_NODE_ID = "new_end_node_id";

    /** Element-info-update payload: snapshot of user-edited field values, encoded as a sub-compound. */
    public static final String TAG_PANEL_SNAPSHOT = "panel_snapshot";

    /**
     * Combine-cascade payload: pair list of {@code (survivor_node_id, absorbed_node_id)} per merge
     * request. Encoded as a {@link com.minecart.serialization.tag.ListTag} of compounds with the
     * two id fields below. The server walks each request through the cascade engine in order; the
     * whole batch is atomic.
     */
    public static final String TAG_COMBINE_PAIRS = "combine_pairs";
    public static final String TAG_SURVIVOR_NODE_ID = "survivor_node_id";
    public static final String TAG_ABSORBED_NODE_ID = "absorbed_node_id";
    /**
     * Combine-cascade payload: optional drag gesture id so the server can correlate the merge
     * with an active drag lease (Phase 2c). Absent on combines not initiated from a drag (e.g.
     * panel-driven port edits).
     */
    public static final String TAG_GESTURE_ID = "gesture_id";

    /**
     * Rotate-element payload tags. {@code pivot_x/y} is the world-space rotation pivot (cursor
     * position for scroll / hold gestures, component centre for panel-driven rotates);
     * {@code delta_radians} is the rotation amount around that pivot.
     */
    public static final String TAG_PIVOT_X = "pivot_x";
    public static final String TAG_PIVOT_Y = "pivot_y";
    public static final String TAG_DELTA_RADIANS = "delta_radians";

    /** Snapshot body: embedded circuit compound under the payload root. */
    public static final String SNAPSHOT_TAG_CIRCUIT = "circuit";

    public static final String ELEMENT_TAG_DATA = "data";

    public static final String KIND_INSERT = "insert";
    public static final String KIND_REMOVE = "remove";
    public static final String KIND_CHANGE = "change";
    public static final String KIND_RENAME = "rename";
}
