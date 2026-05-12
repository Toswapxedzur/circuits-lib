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

    public static final String PAYLOAD_ENVELOPE_ID = "payload_id";

    public static final String TAG_WORLD_ID = "world_id";
    public static final String TAG_CIRCUIT_ID = "circuit_id";
    public static final String TAG_ELEMENT_ID = "element_id";
    public static final String TAG_ACTION = "action";
    public static final String TAG_CHANGES = "changes";
    public static final String TAG_KIND = "kind";

    /** Snapshot body: embedded circuit compound under the payload root. */
    public static final String SNAPSHOT_TAG_CIRCUIT = "circuit";

    /** {@link com.minecart.registry.CircuitElementType} id string (same as {@link com.minecart.misc.CoreStrings#ELEMENT_TYPE} in element tags). */
    public static final String ELEMENT_TAG_ELEMENT_REGISTRY_ID = "element_type_id";
    public static final String ELEMENT_TAG_DATA = "data";

    public static final String KIND_INSERT = "insert";
    public static final String KIND_REMOVE = "remove";
    public static final String KIND_CHANGE = "change";
}
