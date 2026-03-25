package com.minecart.logic;

import com.minecart.registry.CircuitElementType;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.type.ElectricalInfo;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A group of nodes and edges constitute to a circuit component, which could provide extra relations
 */
public class CircuitComponent extends CircuitElement {
    protected Set<CircuitNode> nodes;
    protected Set<CircuitEdge> edges;

    public CircuitComponent(){
        super();
        nodes = new LinkedHashSet<>();
        edges = new LinkedHashSet<>();
    }

    /**
     * Generate the local nodes and edges, not connected to the outside.
     * Subclasses should populate the 'nodes' and 'edges' sets here.
     */
    protected void generate(){

    }

    // 1. Basic Node Creation
    protected <T extends CircuitNode> T newNode(CircuitElementType<T> type){
        T node = getWorld().createNode(type);
        nodes.add(node);
        return node;
    }

    // 2. Variate Node Creation (Data attached)
    protected <I extends ElectricalInfo, T extends CircuitNode & ElectricalVariate<I>> T newNode(CircuitElementType<T> type, I info){
        T node = newNode(type);
        node.set(info);
        return node;
    }

    // 3. Basic Edge Creation
    protected <T extends CircuitEdge> T newEdge(CircuitElementType<T> type, CircuitNode node1, CircuitNode node2){
        // Instantiate the custom edge (e.g., ResistorEdge, WireEdge)
        T edge = world.connect(type, node1, node2);
        edges.add(edge);
        return edge;
    }

    // 4. BONUS: Variate Edge Creation (e.g., A Resistor Edge that needs an Ohms value)
    protected <I extends ElectricalInfo, T extends CircuitEdge & ElectricalVariate<I>> T newEdge(CircuitElementType<T> type, CircuitNode node1, CircuitNode node2, I info){
        T edge = newEdge(type, node1, node2);
        edge.set(info);
        return edge;
    }

    /**
     * Maps an external connection index (e.g., 0 for North side, 1 for South side)
     * to the specific internal CircuitNode acting as that port.
     */
    protected CircuitNode getPort(int index){
        return null;
    }

    /**
     * Routes the external edge to the correct internal port node.
     */
    protected boolean connect(CircuitEdge edge, int index, boolean simulate){
        CircuitNode port = getPort(index);
        if (port == null) return false;

        return port.connectEdge(edge, simulate);
    }

    /**
     * Searches the internal nodes to find where this edge is attached, and disconnects it.
     */
    protected boolean disconnect(CircuitEdge edge, boolean simulate){
        for (CircuitNode node : nodes) {
            // Check if this node is actually holding the edge
            if (node.getConnection().contains(edge)) {
                return node.disconnect(edge, simulate);
            }
        }
        return false;
    }

    /**
     * Safely cleans up the component, breaking it into its base elements for the CircuitManager.
     */
    public void destroy(Circuit masterCircuit) {
        for (CircuitNode node : nodes) {
            masterCircuit.destroy(node, false);
        }
    }
}
