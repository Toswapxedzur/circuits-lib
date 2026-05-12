package com.minecart.logic;

import com.minecart.math.LinearSystem;
import com.minecart.misc.CoreStrings;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.ElectricalInfo;
import com.minecart.event.events.ElementEvent;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.registry.CircuitElementType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.ListTag;
import com.minecart.serialization.tag.Tag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A group of nodes and edges constitute to a circuit component, which could provide extra relations
 */
public non-sealed class CircuitComponent extends CircuitElement {
    protected Set<CircuitNode> nodes;
    protected Set<CircuitEdge> edges;

    public CircuitComponent(){
        super();
        nodes = new LinkedHashSet<>();
        edges = new LinkedHashSet<>();
    }

    @Override
    public void setCircuit(Circuit circuit) {
        super.setCircuit(circuit);
        if (circuit != null && circuit.getWorld() != null) {
            setWorld(circuit.getWorld());
        }
    }

    /**
     * Extra constitutive relations beyond branch/device equations (e.g. controlled sources). Default: none.
     */
    public void collectRule(LinearSystem.RelationProvider equations) {
    }

    /**
     * Generate the local nodes and edges, not connected to the outside.
     * Subclasses should populate the 'nodes' and 'edges' sets here.
     */
    public void generate(){
    }

    // 1. Basic Node Creation
    protected <T extends CircuitNode> T newNode(CircuitElementType<T> type){
        ServerWorld serverWorld = (ServerWorld) getWorld();
        T node = serverWorld.createNodeForComponent(type);
        nodes.add(node);
        node.setComponent(this);
        return node;
    }

    protected <T extends CircuitNode & ElectricalVariate<O>, O extends ElectricalInfo> T newNode(
            CircuitElementType<T> type, O propertyInfo) {
        ServerWorld serverWorld = (ServerWorld) getWorld();
        T node = serverWorld.createNodeForComponent(type, propertyInfo);
        nodes.add(node);
        node.setComponent(this);
        return node;
    }

    protected <T extends CircuitNode & ElectricalVariate<?>> T newNode(
            CircuitElementType<T> type, int propertyIndex, Object property) {
        ServerWorld serverWorld = (ServerWorld) getWorld();
        T node = serverWorld.createNodeForComponent(type);
        node.set(propertyIndex, property);
        nodes.add(node);
        node.setComponent(this);
        return node;
    }

    // 3. Basic Edge Creation
    protected <T extends CircuitEdge> T newEdge(CircuitElementType<T> type, CircuitNode node1, CircuitNode node2){
        ServerWorld serverWorld = (ServerWorld) getWorld();
        T edge = serverWorld.connectInComponent(type, node1, node2);
        edges.add(edge);
        edge.setComponent(this);
        return edge;
    }

    protected <T extends CircuitEdge & ElectricalVariate<O>, O extends ElectricalInfo> T newEdge(
            CircuitElementType<T> type, CircuitNode node1, CircuitNode node2, O propertyInfo) {
        ServerWorld serverWorld = (ServerWorld) getWorld();
        T edge = serverWorld.connectInComponent(type, node1, node2, propertyInfo);
        edges.add(edge);
        edge.setComponent(this);
        return edge;
    }

    protected <T extends CircuitEdge & ElectricalVariate<?>> T newEdge(
            CircuitElementType<T> type, CircuitNode node1, CircuitNode node2, int propertyIndex, Object property) {
        ServerWorld serverWorld = (ServerWorld) getWorld();
        T edge = serverWorld.connectInComponent(type, node1, node2);
        if (edge != null) {
            edge.set(propertyIndex, property);
        }
        edges.add(edge);
        edge.setComponent(this);
        return edge;
    }

    /**
     * Maps an external connection index (e.g., 0 for North side, 1 for South side)
     * to the specific internal CircuitNode acting as that port. Public so callers (renderer, server-side
     * placement handlers, anchor wiring) can look up a port without going through {@link #connect}.
     */
    public CircuitNode getPort(int index){
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
    public void destroy(ServerCircuit masterCircuit) {
        World w = masterCircuit.getWorld();
        if (w != null && w.post(new ElementEvent.ElementRemoveEvent(w, this))) {
            return;
        }
        for (CircuitNode node : nodes) {
            masterCircuit.destroy(node, false);
        }
    }

    /**
     * Removes internal structural nodes without {@link ElementEvent} (topology replication / mirror apply).
     */
    public void destroyForTopologyMirror(Circuit circuit) {
        for (CircuitNode node : new ArrayList<>(nodes)) {
            circuit.destroyNodeForTopologyMirror(node);
        }
    }

    @Override
    public void save(CompoundTag tag) {
        super.save(tag);
        ListTag nodeIds = new ListTag();
        for (CircuitNode n : nodes) {
            nodeIds.add(TagUtil.writeUUID(n.getId()));
        }
        tag.put(CoreStrings.NODE_IDS, nodeIds);
        ListTag edgeIds = new ListTag();
        for (CircuitEdge e : edges) {
            edgeIds.add(TagUtil.writeUUID(e.getId()));
        }
        tag.put(CoreStrings.EDGE_IDS, edgeIds);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        Circuit c = getCircuit();
        if (c == null) {
            throw new IllegalStateException("CircuitComponent has no circuit");
        }
        nodes.clear();
        edges.clear();
        Tag nodeIdsTag = tag.get(CoreStrings.NODE_IDS);
        if (nodeIdsTag instanceof ListTag nl) {
            for (int i = 0; i < nl.size(); i++) {
                UUID nu = TagUtil.readUUID(nl.get(i));
                if (nu == null) {
                    nu = TagUtil.parseUuidStringTag(nl.get(i));
                }
                if (nu == null) {
                    continue;
                }
                CircuitNode n = c.findNode(nu);
                if (n != null) {
                    nodes.add(n);
                }
            }
        }
        Tag edgeIdsTag = tag.get(CoreStrings.EDGE_IDS);
        if (edgeIdsTag instanceof ListTag el) {
            for (int i = 0; i < el.size(); i++) {
                UUID eu = TagUtil.readUUID(el.get(i));
                if (eu == null) {
                    eu = TagUtil.parseUuidStringTag(el.get(i));
                }
                if (eu == null) {
                    continue;
                }
                CircuitEdge e = c.findEdge(eu);
                if (e != null) {
                    edges.add(e);
                }
            }
        }
        for (CircuitNode n : nodes) {
            n.setComponent(this);
        }
    }
}
