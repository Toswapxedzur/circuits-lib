package com.minecart.misc;

/**
 * Stable tag keys and wire strings for the core module (circuits, elements, actions).
 */
public final class CoreStrings {

    private CoreStrings() {
    }

    public static final String ACTION_TAG_ID = "action_id";

    public static final String CIRCUIT_ID = "circuit_id";
    public static final String NODES = "nodes";
    public static final String EDGES = "edges";
    public static final String COMPONENTS = "components";
    public static final String NODE_IDS = "node_ids";
    public static final String EDGE_IDS = "edge_ids";

    public static final String ELEMENT_ID = "id";
    public static final String ELEMENT_TYPE = "type";

    public static final String NODE_GROUND = "ground";
    public static final String NODE_VOLTAGE = "voltage";
    public static final String NODE_COMPONENT = "component";

    public static final String EDGE_START = "start";
    public static final String EDGE_END = "end";
    public static final String EDGE_CURRENT = "current";
    public static final String EDGE_OVERPOWERED = "overpowered";
    /** Subtag for {@link com.minecart.elements.edge.Diode} variant parameters. */
    public static final String EDGE_DIODE_INFO = "diode_info";
}
