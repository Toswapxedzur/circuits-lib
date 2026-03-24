package com.minecart.logic;

import com.minecart.math.function.DoubleVar;
import com.minecart.math.function.LinearSystem;
import org.apache.commons.lang3.mutable.MutableBoolean;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Represent a connected bidirectional circuit network
 */
public class Circuit{
    protected World world;

    protected final UUID id;

    protected Set<CircuitNode> nodes;
    protected Set<CircuitEdge> edges;
    protected Set<CircuitComponent> components;

    protected boolean dirty;

    /**
     * Invoke this method when the electrical rules changes, few circumstances could cause this to happen:
     * <p> 1. Nodes or edges is added or removed
     * <p> 2. The information inside node is changed that will affect the equation collected
     */
    public void markDirty() {
        this.dirty = true;
    }

    public World getWorld() {
        return world;
    }

    public void setWorld(World world) {
        this.world = world;
    }

    protected LinearSystem system;

    public Circuit(){
        id = UUID.randomUUID();
        nodes = new LinkedHashSet<>();
        edges = new LinkedHashSet<>();
        system = new LinearSystem();
        dirty = false;
    }

    public void tick(){
        if(dirty) {
            update();
            system.solve();
            dirty = false;
        }

        for(CircuitNode node : nodes){
            node.tick();
        }
        for(CircuitEdge edge : edges){
            edge.tick();
        }
    }

    public void bfs(CircuitNode startNode, Consumer<CircuitNode> nodeConsumer, Consumer<CircuitEdge> edgeConsumer) {
        if (startNode == null || !this.nodes.contains(startNode)) return;

        Set<CircuitNode> visitedNodes = new HashSet<>();
        Set<CircuitEdge> visitedEdges = new HashSet<>();
        Queue<CircuitNode> queue = new LinkedList<>();

        queue.add(startNode);
        visitedNodes.add(startNode);

        while (!queue.isEmpty()) {
            CircuitNode current = queue.poll();

            // 1. Process the Node
            if (nodeConsumer != null) {
                nodeConsumer.accept(current);
            }

            // 2. Traverse outgoing connections
            for (CircuitEdge edge : current.getConnection()) {

                // Process the edge only if we haven't seen it yet
                if (!visitedEdges.contains(edge)) {
                    visitedEdges.add(edge);
                    if (edgeConsumer != null) {
                        edgeConsumer.accept(edge);
                    }
                }

                CircuitNode neighbor = edge.getOther(current);

                // Queue the neighbor if it hasn't been visited
                if (neighbor != null && !visitedNodes.contains(neighbor)) {
                    visitedNodes.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
    }

    public boolean destroy(CircuitNode node, boolean simulate) {
        if (simulate) {
            return this.nodes.contains(node);
        }

        List<CircuitEdge> connectedEdges = new ArrayList<>(node.getConnection());
        for (CircuitEdge edge : connectedEdges) {
            this.world.disconnect(edge);
        }

        this.nodes.remove(node);
        this.markDirty();
        return true;
    }

    /**
     * Called everytime when the circuit structure changes, not when electrical variable changes
     */
    protected void update(){
        system.collectVar(this::collectVariable);
        system.init();
        system.stampRelation(this::collectRelation);
    }

    public void collectRelation(LinearSystem.RelationProvider provider){
        for(CircuitNode node : nodes){
            node.collectRule(provider);
        }
        for(CircuitEdge edge : edges){
            edge.collectRule(provider);
        }
    }

    public void collectVariable(Set<DoubleVar> collector){
        for(CircuitNode node : nodes){
            node.collectVariable(collector);
        }
        for(CircuitEdge edge : edges){
            edge.collectVariable(collector);
        }
    }

    /**
     * Merge all the nodes and edges to the other circuit, only handles Circuit scope, doesn't care about World
     * @param toMerge the other circuit to merge into, current circuit is discarded
     */
    public void mergeInto(Circuit toMerge){
        for(CircuitNode node : nodes){
            node.setCircuit(toMerge);
            toMerge.nodes().add(node);
        }
        for(CircuitEdge edge : edges){
            edge.setCircuit(toMerge);
            toMerge.edges().add(edge);
        }
    }

    /**
     * Disconnect the edge and seperate the two node
     * @return Whether the two node is still connected
     */
    public boolean seperate(CircuitNode node1, CircuitNode node2, CircuitEdge edge, Circuit newCircuit){
        node1.disconnect(edge, false);
        node2.disconnect(edge, false);

        // ADDED: The edge is broken, remove it from this circuit completely
        this.edges.remove(edge);

        MutableBoolean contain2 = new MutableBoolean(false);
        Consumer<CircuitNode> checkConsumer = node -> {
            if(node == node2)
                contain2.setTrue();
        };
        bfs(node1, checkConsumer, e -> {});

        if(contain2.isTrue()) {
            this.markDirty();
            return false;
        }

        Consumer<CircuitNode> reassignNode = circuitNode -> {
            circuitNode.setCircuit(newCircuit);
            newCircuit.addNode(circuitNode);
            this.nodes.remove(circuitNode);
        };
        Consumer<CircuitEdge> reassignEdge = circuitEdge -> {
            circuitEdge.setCircuit(newCircuit);
            newCircuit.addEdge(circuitEdge);
            this.edges.remove(circuitEdge);
        };

        bfs(node2, reassignNode, reassignEdge);
        this.markDirty();
        newCircuit.markDirty();
        return true;
    }

    public void addEdge(CircuitEdge edge) {
        this.edges.add(edge);
        edge.setCircuit(this);
    }

    public void addNode(CircuitNode node) {
        this.nodes.add(node);
        node.setCircuit(this);
    }

    public Set<CircuitNode> nodes() {
        return nodes;
    }

    public Set<CircuitEdge> edges() {
        return edges;
    }

    public Set<CircuitNode> adjacentNodes(CircuitNode node) {
        return node.getAdjacent().stream().collect(Collectors.toSet());
    }

    public Set<CircuitEdge> incidentEdges(CircuitNode node) {
        return node.getConnection();
    }

    public Set<CircuitEdge> adjacentEdges(CircuitEdge edge) {
        Set<CircuitEdge> set = new LinkedHashSet<>();
        edge.getStart().getConnection().forEach(e -> set.add(e));
        edge.getEnd().getConnection().forEach(e -> set.add(e));
        return set;
    }

    public Set<CircuitEdge> edgesConnecting(CircuitNode nodeU, CircuitNode nodeV) {
        return nodeU.getConnection().stream()
                .filter(e -> {
                    return e.connectTo(nodeV);
                })
                .collect(Collectors.toSet());
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}