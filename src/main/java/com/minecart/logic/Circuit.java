package com.minecart.logic;

import com.google.common.graph.*;
import com.minecart.logic.component.CircuitNode;
import com.minecart.logic.edge.CircuitEdge;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class Circuit implements Network<CircuitNode, CircuitEdge> {
    public static ElementOrder<CircuitNode> nodeOrder = (ElementOrder<CircuitNode>) ElementOrder.sorted(CircuitNode.comparator);
    public static ElementOrder<CircuitEdge> edgeOrder = (ElementOrder<CircuitEdge>) ElementOrder.sorted(CircuitNode.comparator);
    protected Set<CircuitNode> nodes;
    protected Set<CircuitEdge> edges;

    public Circuit(){
        nodes = new TreeSet<>();
        edges = new TreeSet<>();
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
        return nodeOrder;
    }

    @Override
    public ElementOrder<CircuitEdge> edgeOrder() {
        return edgeOrder;
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
        return Arrays.stream(edge.getConnection()).<CircuitEdge>mapMulti((p, consumer) -> {
            p.getConnection().stream().forEach(t -> consumer.accept(t));
        }).collect(Collectors.toSet());
    }

    @Override
    public Graph<CircuitNode> asGraph() {
        return Graphs.copyOf(this).asGraph();
    }

    @Override
    public Set<CircuitEdge> edgesConnecting(CircuitNode nodeU, CircuitNode nodeV) {
        return nodeU.getConnection().stream()
                .filter(e -> {
                    CircuitNode[] conn = e.getConnection();
                    // Directed network: U must be the source [0], V must be the target [1]
                    return conn[0] == nodeU && conn[1] == nodeV;
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
