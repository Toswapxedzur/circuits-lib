package com.minecart.logic;

import com.google.common.graph.*;
import com.minecart.logic.component.CircuitNode;
import com.minecart.logic.component.CircuitEdge;
import com.minecart.math.function.EquationSystem;
import com.minecart.math.function.Expression;
import com.minecart.misc.ElectricalVariable;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.minecart.math.function.Expression.ExpressionBuilder.var;

/**
 * Represent a connected bidirectional circuit network
 */
public class Circuit implements Network<CircuitNode, CircuitEdge> {
    public static final ElementOrder<CircuitNode> NODE_ORDER = (ElementOrder<CircuitNode>) ElementOrder.sorted(CircuitNode.comparator);
    public static final ElementOrder<CircuitEdge> EDGE_ORDER = (ElementOrder<CircuitEdge>) ElementOrder.sorted(CircuitNode.comparator);

    protected World world;
    protected Set<CircuitNode> nodes;
    protected Set<CircuitEdge> edges;

    public World getWorld() {
        return world;
    }

    public void setWorld(World world) {
        this.world = world;
    }

    public List<ElectricalVariable> getElectricalVariables() {
        return electricalVariables;
    }

    // Converted to Lists for deterministic matrix mapping
    protected List<ElectricalVariable> electricalVariables;

    public List<Expression> getElectricalRules() {
        return electricalRules;
    }

    protected List<Expression> electricalRules;

    public EquationSystem getSystem() {
        return system;
    }

    protected EquationSystem system;

    public Circuit(){
        nodes = new TreeSet<>();
        edges = new TreeSet<>();
        electricalVariables = new ArrayList<>();
        electricalRules = new ArrayList<>();
        system = new EquationSystem(electricalRules);
    }

    public void tick(){
        for(CircuitNode node : nodes){
            node.tick();
        }
        for(CircuitEdge edge : edges){
            edge.tick();
        }
        system.solveLinear();
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
        if(simulate)
            return node.destroy(new ArrayList<>(), true);
        List<CircuitEdge> destroyEdge = new ArrayList<>();
        node.destroy(destroyEdge, false);
        for(CircuitEdge edge : destroyEdge){
            edges.remove(edge);
        }
        nodes.remove(node);
        updateTopology();
        return true;
    }

    /**
     * Called everytime when the circuit structure changes, not when electrical variable changes
     */
    public void updateTopology(){
        //update circuit rules and circuit variables
        electricalVariables.clear();
        electricalRules.clear();

        for(CircuitNode node : nodes){
            node.collectRule(electricalRules);
            node.collectElectricalVariable(electricalVariables);
        }
        for(CircuitEdge edge : edges){
            edge.collectRule(electricalRules);
            edge.collectElectricalVariable(electricalVariables);
        }

        if (!edges.isEmpty()) {
            CircuitEdge randomWire = edges.iterator().next();
            electricalRules.add(var(randomWire.getVoltage()));
        }
    }

    /**
     * Merge all the nodes and edges to the other circuit, only handles Circuit and Component scope, doesn't care about World
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

    public boolean seperate(CircuitNode node1, CircuitNode node2, CircuitEdge edge, Circuit newCircuit){
        node1.disconnect(edge, false);
        node2.disconnect(edge, false);
        MutableBoolean contain2 = new MutableBoolean(false);
        Consumer<CircuitNode> checkConsumer = node -> {
            if(node == node2)
                contain2.setTrue();
        };
        bfs(node1, checkConsumer, e -> {});
        if(contain2.isTrue())
            return false;
        Consumer<CircuitNode> reassignNode = circuitNode -> {
            circuitNode.setCircuit(newCircuit);
        };
        Consumer<CircuitEdge> reassignEdge = circuitEdge -> {
            circuitEdge.setCircuit(newCircuit);
        };
        bfs(node2, reassignNode, reassignEdge);
        this.updateTopology();
        newCircuit.updateTopology();
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

    @Override
    public Set<CircuitNode> nodes() {
        return nodes;
    }

    @Override
    public Set<CircuitEdge> edges() {
        return edges;
    }

    @Override
    public boolean isDirected() {
        return true;
    }

    @Override
    public boolean allowsParallelEdges() {
        return true;
    }

    @Override
    public boolean allowsSelfLoops() {
        return true;
    }

    @Override
    public ElementOrder<CircuitNode> nodeOrder() {
        return NODE_ORDER;
    }

    @Override
    public ElementOrder<CircuitEdge> edgeOrder() {
        return EDGE_ORDER;
    }

    @Override
    public Set<CircuitNode> adjacentNodes(CircuitNode node) {
        return node.adjacentNode();
    }

    @Override
    public Set<CircuitNode> predecessors(CircuitNode node) {
        return node.adjacentInNode();
    }

    @Override
    public Set<CircuitNode> successors(CircuitNode node) {
        return node.adjacentOutNode();
    }

    @Override
    public Set<CircuitEdge> incidentEdges(CircuitNode node) {
        return node.getConnection();
    }

    @Override
    public Set<CircuitEdge> inEdges(CircuitNode node) {
        return node.getInConnection();
    }

    @Override
    public Set<CircuitEdge> outEdges(CircuitNode node) {
        return node.getOutConnection();
    }

    @Override
    public int degree(CircuitNode node) {
        return node.getAmountConnected();
    }

    @Override
    public int inDegree(CircuitNode node) {
        return node.getInAmountConnected();
    }

    @Override
    public int outDegree(CircuitNode node) {
        return node.getOutAmountConnected();
    }

    @Override
    public EndpointPair<CircuitNode> incidentNodes(CircuitEdge edge) {
        return edge.incidentNodes();
    }

    @Override
    public Set<CircuitEdge> adjacentEdges(CircuitEdge edge) {
        return edge.getConnections().stream().<CircuitEdge>mapMulti((p, consumer) -> {
            p.getConnection().stream().forEach(t -> consumer.accept(t));
        }).filter(e -> e != edge).collect(Collectors.toSet());
    }

    @Override
    public Graph<CircuitNode> asGraph() {
        return Graphs.copyOf(this).asGraph();
    }

    @Override
    public Set<CircuitEdge> edgesConnecting(CircuitNode nodeU, CircuitNode nodeV) {
        return nodeU.getConnection().stream()
                .filter(e -> {
                    return e.connectTo(nodeV);
                })
                .collect(Collectors.toSet());
    }

    @Override
    public Set<CircuitEdge> edgesConnecting(EndpointPair<CircuitNode> endpoints) {
        return edgesConnecting(endpoints.nodeU(), endpoints.nodeV());
    }

    @Override
    public Optional<CircuitEdge> edgeConnecting(CircuitNode nodeU, CircuitNode nodeV) {
        return edgesConnecting(nodeU, nodeV).stream().findFirst();
    }

    @Override
    public Optional<CircuitEdge> edgeConnecting(EndpointPair<CircuitNode> endpoints) {
        return edgeConnecting(endpoints.nodeU(), endpoints.nodeV());
    }

    @Override
    public @Nullable CircuitEdge edgeConnectingOrNull(CircuitNode nodeU, CircuitNode nodeV) {
        return edgeConnecting(nodeU, nodeV).orElse(null);
    }

    @Override
    public @Nullable CircuitEdge edgeConnectingOrNull(EndpointPair<CircuitNode> endpoints) {
        return edgeConnectingOrNull(endpoints.nodeU(), endpoints.nodeV());
    }

    @Override
    public boolean hasEdgeConnecting(CircuitNode nodeU, CircuitNode nodeV) {
        return !edgesConnecting(nodeU, nodeV).isEmpty();
    }

    @Override
    public boolean hasEdgeConnecting(EndpointPair<CircuitNode> endpoints) {
        return hasEdgeConnecting(endpoints.nodeU(), endpoints.nodeV());
    }
}