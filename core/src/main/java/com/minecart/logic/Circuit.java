package com.minecart.logic;

import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.ListTag;
import com.minecart.serialization.tag.Tag;
import org.apache.commons.lang3.mutable.MutableBoolean;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Graph and serialized state of a circuit: nodes, edges, and components. Persistence and structural
 * operations live here; {@link ServerCircuit} adds simulation (solver, tick, world).
 */
public class Circuit {
    protected final UUID id;

    /** Owning electrical network; set via {@link #setWorld(World)} or when loading. */
    protected World world;

    protected Set<CircuitNode> nodes;
    protected Set<CircuitEdge> edges;
    protected Set<CircuitComponent> components;

    public Circuit(UUID id) {
        this.id = id;
        nodes = new LinkedHashSet<>();
        edges = new LinkedHashSet<>();
        components = new LinkedHashSet<>();
    }

    public UUID getId() {
        return id;
    }

    public World getWorld() {
        return world;
    }

    public void setWorld(World world) {
        this.world = world;
    }

    public Set<CircuitNode> nodes() {
        return nodes;
    }

    public Set<CircuitEdge> edges() {
        return edges;
    }

    public Set<CircuitComponent> components() {
        return components;
    }

    public CircuitNode findNode(UUID id) {
        if (id == null) {
            return null;
        }
        for (CircuitNode n : nodes) {
            if (id.equals(n.getId())) {
                return n;
            }
        }
        return null;
    }

    public CircuitEdge findEdge(UUID id) {
        if (id == null) {
            return null;
        }
        for (CircuitEdge e : edges) {
            if (id.equals(e.getId())) {
                return e;
            }
        }
        return null;
    }

    /** Searches nodes, edges, and components for an element with the given id. */
    public CircuitElement findElement(UUID id) {
        if (id == null) {
            return null;
        }
        CircuitNode n = findNode(id);
        if (n != null) return n;
        CircuitEdge e = findEdge(id);
        if (e != null) return e;
        for (CircuitComponent c : components) {
            if (id.equals(c.getId())) return c;
        }
        return null;
    }

    public void addEdge(CircuitEdge edge) {
        this.edges.add(edge);
        edge.setCircuit(this);
    }

    public void addNode(CircuitNode node) {
        this.nodes.add(node);
        node.setCircuit(this);
    }

    public void addComponent(CircuitComponent c) {
        components.add(c);
        c.setCircuit(this);
    }

    /**
     * Merge all nodes and edges into {@code toMerge}; this circuit is left empty of those elements.
     */
    public void mergeInto(Circuit toMerge) {
        for (CircuitNode node : nodes) {
            node.setCircuit(toMerge);
            toMerge.nodes().add(node);
        }
        for (CircuitEdge edge : edges) {
            edge.setCircuit(toMerge);
            toMerge.edges().add(edge);
        }
    }

    public void bfs(CircuitNode startNode, Consumer<CircuitNode> nodeConsumer, Consumer<CircuitEdge> edgeConsumer) {
        if (startNode == null || !this.nodes.contains(startNode)) {
            return;
        }

        Set<CircuitNode> visitedNodes = new HashSet<>();
        Set<CircuitEdge> visitedEdges = new HashSet<>();
        Queue<CircuitNode> queue = new LinkedList<>();

        queue.add(startNode);
        visitedNodes.add(startNode);

        while (!queue.isEmpty()) {
            CircuitNode current = queue.poll();

            if (nodeConsumer != null) {
                nodeConsumer.accept(current);
            }

            for (CircuitEdge edge : current.getConnection()) {

                if (!visitedEdges.contains(edge)) {
                    visitedEdges.add(edge);
                    if (edgeConsumer != null) {
                        edgeConsumer.accept(edge);
                    }
                }

                CircuitNode neighbor = edge.getOther(current);

                if (neighbor != null && !visitedNodes.contains(neighbor)) {
                    visitedNodes.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }
    }

    /**
     * Disconnect the edge and split connectivity; nodes/edges on {@code node2}'s side move to {@code newCircuit}.
     *
     * @return {@code false} if the two nodes remain connected without the edge (no split).
     */
    public boolean seperate(CircuitNode node1, CircuitNode node2, CircuitEdge edge, Circuit newCircuit) {
        node1.disconnect(edge, false);
        node2.disconnect(edge, false);

        this.edges.remove(edge);

        MutableBoolean contain2 = new MutableBoolean(false);
        Consumer<CircuitNode> checkConsumer = node -> {
            if (node == node2) {
                contain2.setTrue();
            }
        };
        bfs(node1, checkConsumer, e -> {});

        if (contain2.isTrue()) {
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
        return true;
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
                .filter(e -> e.connectTo(nodeV))
                .collect(Collectors.toSet());
    }

    public void save(CompoundTag tag) {
        TagUtil.putUUID(tag, "circuit_id", getId());
        ListTag nodeList = new ListTag();
        for (CircuitNode node : nodes()) {
            CompoundTag nc = new CompoundTag();
            node.save(nc);
            nodeList.add(nc);
        }
        tag.put("nodes", nodeList);
        ListTag edgeList = new ListTag();
        for (CircuitEdge edge : edges()) {
            CompoundTag ec = new CompoundTag();
            edge.save(ec);
            edgeList.add(ec);
        }
        tag.put("edges", edgeList);
        ListTag compList = new ListTag();
        for (CircuitComponent comp : components()) {
            CompoundTag cc = new CompoundTag();
            comp.save(cc);
            compList.add(cc);
        }
        tag.put("components", compList);
    }

    /**
     * Restores structure from {@code tag}. Requires {@code world} to instantiate elements from registry ids.
     */
    public void load(World world, CompoundTag tag) {
        if (world == null) {
            throw new IllegalArgumentException("world is required to load a circuit");
        }
        this.world = world;
        UUID circuitId = TagUtil.getUUID(tag, "circuit_id");
        if (circuitId == null) {
            throw new IllegalArgumentException("Missing circuit_id");
        }
        if (!circuitId.equals(getId())) {
            throw new IllegalArgumentException("circuit_id mismatch");
        }

        Tag nodesTag = tag.get("nodes");
        if (nodesTag instanceof ListTag nl) {
            for (int i = 0; i < nl.size(); i++) {
                CompoundTag nc = TagUtil.requireCompoundTag(nl.get(i), "nodes[" + i + "]");
                loadNode(world, nc);
            }
        }

        Tag edgesTag = tag.get("edges");
        if (edgesTag instanceof ListTag el) {
            for (int i = 0; i < el.size(); i++) {
                CompoundTag ec = TagUtil.requireCompoundTag(el.get(i), "edges[" + i + "]");
                loadEdge(world, ec);
            }
        }

        Tag compsTag = tag.get("components");
        if (compsTag instanceof ListTag cl) {
            for (int i = 0; i < cl.size(); i++) {
                CompoundTag cc = TagUtil.requireCompoundTag(cl.get(i), "components[" + i + "]");
                loadComponent(world, cc);
            }
        }
    }

    private void loadNode(World world, CompoundTag nc) {
        CircuitElement el = CircuitElement.deserialize(nc, world);
        if (!(el instanceof CircuitNode node)) {
            throw new IllegalArgumentException("Expected node in nodes[] list, got: " + nc.getString("type"));
        }
        addNode(node);
        node.load(nc);
    }

    private void loadEdge(World world, CompoundTag ec) {
        CircuitElement el = CircuitElement.deserialize(ec, world);
        if (!(el instanceof CircuitEdge edge)) {
            throw new IllegalArgumentException("Expected edge in edges[] list, got: " + ec.getString("type"));
        }
        edge.load(ec, this);
        addEdge(edge);
    }

    private void loadComponent(World world, CompoundTag cc) {
        CircuitElement el = CircuitElement.deserialize(cc, world);
        if (!(el instanceof CircuitComponent comp)) {
            throw new IllegalArgumentException("Expected component in components[] list, got: " + cc.getString("type"));
        }
        addComponent(comp);
        comp.load(cc);
    }

    /**
     * Removes a node during topology replication (mirrors). {@link ServerCircuit} and client-side circuits override.
     */
    public boolean destroyNodeForTopologyMirror(CircuitNode node) {
        throw new UnsupportedOperationException(
                "Topology mirror destroy not supported for " + getClass().getSimpleName());
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
