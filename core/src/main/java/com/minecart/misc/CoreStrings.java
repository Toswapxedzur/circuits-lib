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
    /**
     * Subtag list on a {@link com.minecart.logic.CircuitComponent} that records each public port's
     * {@code (portIndex, nodeId)} binding so {@link com.minecart.logic.CircuitComponent#getPort(int)}
     * can be restored from save without per-subclass boilerplate. Each list entry is a {@code CompoundTag}
     * with {@link #PORT_INDEX} (int) and {@link #PORT_NODE_ID} (uuid).
     */
    public static final String PORT_BINDINGS = "port_bindings";
    public static final String PORT_INDEX = "i";
    public static final String PORT_NODE_ID = "u";

    public static final String ELEMENT_ID = "id";
    public static final String ELEMENT_TYPE = "type";
    /**
     * Subtag holding all serializable {@link com.minecart.variant.ElementInfo}s on a
     * {@link com.minecart.logic.CircuitElement}, keyed by {@link com.minecart.registry.ElementInfoType#getTypeId()}.
     */
    public static final String INFOS = "infos";

    public static final String NODE_GROUND = "ground";
    public static final String NODE_VOLTAGE = "voltage";
    public static final String NODE_COMPONENT = "component";

    public static final String EDGE_START = "start";
    public static final String EDGE_END = "end";
    public static final String EDGE_CURRENT = "current";
    public static final String EDGE_OVERPOWERED = "overpowered";
    /** Subtag for {@link com.minecart.elements.edge.Diode} variant parameters. */
    public static final String EDGE_DIODE_INFO = "diode_info";
    /** Subtag for {@link com.minecart.elements.component.BJTransistor} parameters and internal topology ids. */
    public static final String COMPONENT_BJT_INFO = "bjt_info";
}
