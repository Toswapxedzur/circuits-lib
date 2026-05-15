package com.minecart.logic;

import com.minecart.event.events.CircuitLifecycleEvent;
import com.minecart.event.events.ElementEvent;
import com.minecart.event.events.ElementInfoInjectEvent;
import com.minecart.event.events.ServerTickEvent;
import com.minecart.event.events.ShortCircuitEvent;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.registry.CircuitElementType;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.ElectricalInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-side simulation for a {@link World}: circuit ticks, element creation, and short-circuit events.
 */
public class ServerWorld extends World {

    protected final ServerTickEvent.World preTick = new ServerTickEvent.World(ServerTickEvent.Phase.PRE, this);
    protected final ServerTickEvent.World postTick = new ServerTickEvent.World(ServerTickEvent.Phase.POST, this);
    protected final List<CircuitEdge> overpowered = new ArrayList<>();
    protected final ShortCircuitEvent shortCircuitEvent = new ShortCircuitEvent(this, overpowered);

    public ServerWorld(ServerLevel level) {
        super(level);
    }

    /**
     * Server world with a stable id (e.g. matching a client or network-assigned {@link World#getId()}).
     */
    public ServerWorld(ServerLevel level, UUID worldId) {
        super(level, worldId);
    }

    @Override
    public ServerLevel getLevel() {
        return (ServerLevel) level;
    }

    @Override
    public void addCircuit(Circuit circuit) {
        super.addCircuit(circuit);
        if (circuit instanceof ServerCircuit sc) {
            sc.setWorld(this);
        }
        post(new CircuitLifecycleEvent.CircuitInsertEvent(this, circuit));
    }

    @Override
    public boolean removeCircuit(Circuit circuit) {
        boolean removed = super.removeCircuit(circuit);
        if (removed) {
            post(new CircuitLifecycleEvent.CircuitRemoveEvent(this, circuit));
        }
        return removed;
    }

    public void tick() {
        post(preTick);
        for (Circuit c : circuits) {
            if (c instanceof ServerCircuit sc) {
                sc.tick();
                for (CircuitEdge e : sc.drainOverpoweredThisTick()) {
                    setOverpowered(e);
                }
            }
        }
        if (!overpowered.isEmpty()) {
            post(shortCircuitEvent);
        }
        overpowered.clear();
        post(postTick);
    }

    public <T extends CircuitNode> T createNode(CircuitElementType<T> type) {
        return createNodeInternal(type, false);
    }

    /**
     * Creates a node when building internal structure from {@link CircuitComponent} (allows {@link CircuitElementType#isUnusual()} types).
     */
    protected <T extends CircuitNode> T createNodeForComponent(CircuitElementType<T> type) {
        return createNodeInternal(type, true);
    }

    private <T extends CircuitNode> T createNodeInternal(CircuitElementType<T> type, boolean allowUnusual) {
        T node = type.create(this, allowUnusual);
        node.setWorld(this);
        ServerCircuit circuit = createCircuit();
        node.setCircuit(circuit);
        post(new ElementInfoInjectEvent(this, node));
        if (post(new ElementEvent.ElementInsertEvent(this, node))) {
            removeCircuit(circuit);
            throw new IllegalStateException("Circuit element insert cancelled");
        }
        circuit.nodes().add(node);
        circuit.markDirty();
        return node;
    }

    /**
     * Like {@link #createNode(CircuitElementType)} but applies {@link ElectricalVariate#set(ElectricalInfo)} after insert.
     */
    public <T extends CircuitNode & ElectricalVariate<O>, O extends ElectricalInfo> T createNode(
            CircuitElementType<T> type, O propertyInfo) {
        T node = createNode(type);
        node.set(propertyInfo);
        return node;
    }

    /**
     * Like {@link #createNodeForComponent(CircuitElementType)} but applies {@link ElectricalVariate#set(ElectricalInfo)} after insert.
     */
    protected <T extends CircuitNode & ElectricalVariate<O>, O extends ElectricalInfo> T createNodeForComponent(
            CircuitElementType<T> type, O propertyInfo) {
        T node = createNodeForComponent(type);
        node.set(propertyInfo);
        return node;
    }

    /**
     * Creates a {@link CircuitComponent} on {@code circuit}, then applies {@link ElectricalVariate#set(ElectricalInfo)}.
     */
    public <T extends CircuitComponent & ElectricalVariate<O>, O extends ElectricalInfo> T createComponent(
            ServerCircuit circuit, CircuitElementType<T> type, O propertyInfo) {
        T comp = type.create(this, false);
        circuit.addComponent(comp);
        comp.set(propertyInfo);
        post(new ElementInfoInjectEvent(this, comp));
        circuit.markDirty();
        return comp;
    }

    /**
     * Creates a {@link CircuitComponent}, attaches it to a fresh {@link ServerCircuit}, fires the standard
     * insert events, and runs {@link CircuitComponent#generate()} so its internal nodes/edges exist. Used by
     * the server-side placement pipeline when no {@link ElectricalVariate} info is being supplied (e.g. UI
     * "drop a fresh BJT" with default beta).
     */
    public <T extends CircuitComponent> T createComponent(CircuitElementType<T> type) {
        if (type.isUnusual()) {
            throw new IllegalStateException(
                    "Unusual element type '" + type.getTypeId() + "' cannot be created via createComponent");
        }
        T comp = type.create(this, false);
        comp.setWorld(this);
        ServerCircuit circuit = createCircuit();
        circuit.addComponent(comp);
        post(new ElementInfoInjectEvent(this, comp));
        // Generate internal nodes/edges BEFORE posting the component's own InsertEvent. Each
        // newNode/newEdge inside generate() fires its own InsertEvent which the network listener
        // batches into the outgoing payload; emitting them before the component's InsertEvent means
        // the client sees the internals first, so the subsequent component INSERT's load() call can
        // successfully look them up via findNode/findEdge and apply setComponent(this). With the old
        // order the component arrived first, load() couldn't find any of the still-pending internals,
        // and they were never linked back — leaving them visible (no component owner) on the client
        // canvas.
        comp.generate();
        if (post(new ElementEvent.ElementInsertEvent(this, comp))) {
            removeCircuit(circuit);
            throw new IllegalStateException("Component insert cancelled");
        }
        circuit.markDirty();
        return comp;
    }

    protected ServerCircuit createCircuit() {
        ServerCircuit circuit = new ServerCircuit();
        addCircuit(circuit);
        return circuit;
    }

    public <T extends CircuitEdge> T connect(CircuitElementType<T> type, CircuitNode node1, CircuitNode node2) {
        return connectInternal(type, node1, node2, false);
    }

    /**
     * Connects two nodes with an edge when building from {@link CircuitComponent} (allows {@link CircuitElementType#isUnusual()} types).
     */
    protected <T extends CircuitEdge> T connectInComponent(CircuitElementType<T> type, CircuitNode node1, CircuitNode node2) {
        return connectInternal(type, node1, node2, true);
    }

    protected <T extends CircuitEdge> T connectInternal(
            CircuitElementType<T> type, CircuitNode node1, CircuitNode node2, boolean allowUnusual) {
        if (node1.getWorld() != this || node2.getWorld() != this) {
            throw new IllegalArgumentException("Can't coonect node that doesn't belong to the current ServerWorld");
        }
        T edge = type.create(this, allowUnusual);
        edge.setWorld(this);
        if (!edge.connect(node1, node2, true)) {
            return null;
        }
        edge.connect(node1, node2, false);
        if (!node1.connectEdge(edge, true) || !node2.connectEdge(edge, true)) {
            return null;
        }
        node1.connectEdge(edge, false);
        node2.connectEdge(edge, false);
        post(new ElementInfoInjectEvent(this, edge));
        if (post(new ElementEvent.ElementInsertEvent(this, edge))) {
            node1.disconnect(edge, false);
            node2.disconnect(edge, false);
            edge.disconnect(false);
            return null;
        }
        Circuit circuit1 = node1.getCircuit();
        Circuit circuit2 = node2.getCircuit();
        if (circuit1 != circuit2) {
            circuit2.mergeInto(circuit1);
            removeCircuit(circuit2);
        }
        circuit1.addEdge(edge);
        ((ServerCircuit) circuit1).markDirty();
        return edge;
    }

    /**
     * Like {@link #connect(CircuitElementType, CircuitNode, CircuitNode)} but applies {@link ElectricalVariate#set(ElectricalInfo)}
     * after the edge is placed.
     */
    public <T extends CircuitEdge & ElectricalVariate<O>, O extends ElectricalInfo> T connect(
            CircuitElementType<T> type, CircuitNode node1, CircuitNode node2, O propertyInfo) {
        T edge = connect(type, node1, node2);
        if (edge != null) {
            edge.set(propertyInfo);
        }
        return edge;
    }

    /**
     * Like {@link #connectInComponent(CircuitElementType, CircuitNode, CircuitNode)} but applies {@link ElectricalVariate#set(ElectricalInfo)}.
     */
    protected <T extends CircuitEdge & ElectricalVariate<O>, O extends ElectricalInfo> T connectInComponent(
            CircuitElementType<T> type, CircuitNode node1, CircuitNode node2, O propertyInfo) {
        T edge = connectInComponent(type, node1, node2);
        if (edge != null) {
            edge.set(propertyInfo);
        }
        return edge;
    }

    public boolean disconnect(CircuitEdge edge) {
        if (post(new ElementEvent.ElementRemoveEvent(this, edge))) {
            return false;
        }
        return disconnectWithoutRemoveEvent(edge);
    }

    /**
     * Same as {@link #disconnect(CircuitEdge)} but without firing {@link ElementRemoveEvent}
     * (used when removing edges as part of destroying a node).
     */
    boolean disconnectWithoutRemoveEvent(CircuitEdge edge) {
        CircuitNode node1 = edge.getConnection(0);
        CircuitNode node2 = edge.getConnection(1);
        if (node1.getCircuit() != node2.getCircuit()) {
            return false;
        }
        ServerCircuit circuit = (ServerCircuit) node1.getCircuit();
        if (!node1.disconnect(edge, true) || !node2.disconnect(edge, true) || !edge.disconnect(true)) {
            return false;
        }
        ServerCircuit newCircuit = new ServerCircuit();
        newCircuit.setWorld(this);
        boolean createCircuit = circuit.seperate(node1, node2, edge, newCircuit);
        if (createCircuit) {
            addCircuit(newCircuit);
        }
        return true;
    }

    public boolean destroy(CircuitNode node) {
        if (node.getWorld() != this) {
            throw new IllegalArgumentException("Can't connect node that doesn't belong to the current ServerWorld");
        }

        ServerCircuit circuit = (ServerCircuit) node.getCircuit();

        if (!circuit.destroy(node, true)) {
            return false;
        }

        if (post(new ElementEvent.ElementRemoveEvent(this, node))) {
            return false;
        }

        circuit.destroy(node, false);

        if (circuit.nodes().isEmpty()) {
            removeCircuit(circuit);
        }

        return true;
    }

    public void setOverpowered(CircuitEdge edge) {
        if (edge.getWorld() != this) {
            throw new IllegalArgumentException("Short circuited in another world");
        }
        overpowered.add(edge);
    }
}
