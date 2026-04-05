package com.minecart.client;

/**
 * Stable payload ids and tag keys for the client module (network payloads and envelope fields).
 */
public final class ClientStrings {

    private ClientStrings() {
    }

    public static final String PAYLOAD_ACTION = "minecart.action_payload";
    public static final String PAYLOAD_CIRCUIT_TOPOLOGY = "minecart.circuit_topology_payload";
    public static final String PAYLOAD_CIRCUIT_SNAPSHOT = "minecart.circuit_snapshot_payload";
    public static final String PAYLOAD_CIRCUIT_LIFECYCLE = "minecart.circuit_lifecycle_payload";
    public static final String PAYLOAD_WORLD_LIFECYCLE = "minecart.world_lifecycle_payload";

    public static final String PAYLOAD_ENVELOPE_ID = "payload_id";

    public static final String TAG_WORLD_ID = "world_id";
    public static final String TAG_CIRCUIT_ID = "circuit_id";
    public static final String TAG_ELEMENT_ID = "element_id";
    public static final String TAG_ACTION = "action";
    public static final String TAG_CHANGES = "changes";
    public static final String TAG_KIND = "kind";

    /** Snapshot body: embedded circuit compound under the payload root. */
    public static final String SNAPSHOT_TAG_CIRCUIT = "circuit";

    public static final String TOPOLOGY_TAG_ELEMENT_KIND = "element_kind";
    public static final String TOPOLOGY_TAG_DATA = "data";

    public static final String KIND_INSERT = "insert";
    public static final String KIND_REMOVE = "remove";

    public static final String ELEM_NODE = "node";
    public static final String ELEM_EDGE = "edge";
    public static final String ELEM_COMPONENT = "component";
}
