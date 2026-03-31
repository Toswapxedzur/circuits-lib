package com.minecart.logic;

import com.minecart.event.events.Event;
import com.minecart.event.events.ServerTickEvent;
import com.minecart.event.events.ShortCircuitEvent;
import com.minecart.registry.CircuitElementType;

import java.util.ArrayList;
import java.util.List;

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
        T node = type.create(this);
        node.setWorld(this);
        ServerCircuit circuit = createCircuit();
        node.setCircuit(circuit);
        circuit.nodes().add(node);
        circuit.markDirty();
        return node;
    }

    protected ServerCircuit createCircuit() {
        ServerCircuit circuit = new ServerCircuit();
        circuit.setWorld(this);
        circuits.add(circuit);
        return circuit;
    }

    public <T extends CircuitEdge> T connect(CircuitElementType<T> type, CircuitNode node1, CircuitNode node2) {
        if (node1.getWorld() != this || node2.getWorld() != this) {
            throw new IllegalArgumentException("Can't coonect node that doesn't belong to the current ServerWorld");
        }
        T edge = type.create(this);
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
        Circuit circuit1 = node1.getCircuit();
        Circuit circuit2 = node2.getCircuit();
        if (circuit1 != circuit2) {
            circuit2.mergeInto(circuit1);
            circuits.remove(circuit2);
        }
        circuit1.addEdge(edge);
        ((ServerCircuit) circuit1).markDirty();
        return edge;
    }

    public boolean disconnect(CircuitEdge edge) {
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
            this.circuits.add(newCircuit);
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

        circuit.destroy(node, false);

        if (circuit.nodes().isEmpty()) {
            this.circuits.remove(circuit);
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
