package com.minecart.logic;

import com.minecart.registry.CircuitElementType;
import com.minecart.serialization.TagSerializable;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.ListTag;
import com.minecart.serialization.tag.Tag;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A group of nodes and edges constitute to a circuit component, which could provide extra relations
 */
public class CircuitComponent extends CircuitElement implements TagSerializable {
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
        ServerCircuit sc = (ServerCircuit) getCircuit();
        if (sc == null) {
            throw new IllegalStateException("CircuitComponent has no circuit");
        }
        T node = sc.createNode(type);
        nodes.add(node);
        return node;
    }

    // 3. Basic Edge Creation
    protected <T extends CircuitEdge> T newEdge(CircuitElementType<T> type, CircuitNode node1, CircuitNode node2){
        ServerCircuit sc = (ServerCircuit) getCircuit();
        if (sc == null) {
            throw new IllegalStateException("CircuitComponent has no circuit");
        }
        T edge = sc.connect(type, node1, node2);
        edges.add(edge);
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
    public void destroy(ServerCircuit masterCircuit) {
        for (CircuitNode node : nodes) {
            masterCircuit.destroy(node, false);
        }
    }

    @Override
    public void save(CompoundTag tag) throws IOException {
        saveElementHeader(tag);
        ListTag nodeIds = new ListTag();
        for (CircuitNode n : nodes) {
            nodeIds.add(TagUtil.writeUUID(n.getId()));
        }
        tag.put("node_ids", nodeIds);
        ListTag edgeIds = new ListTag();
        for (CircuitEdge e : edges) {
            edgeIds.add(TagUtil.writeUUID(e.getId()));
        }
        tag.put("edge_ids", edgeIds);
    }

    @Override
    public void load(CompoundTag tag) throws IOException {
        Circuit c = getCircuit();
        if (c == null) {
            throw new IOException("CircuitComponent has no circuit");
        }
        nodes.clear();
        edges.clear();
        Tag nodeIdsTag = tag.get("node_ids");
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
        Tag edgeIdsTag = tag.get("edge_ids");
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
    }
}
