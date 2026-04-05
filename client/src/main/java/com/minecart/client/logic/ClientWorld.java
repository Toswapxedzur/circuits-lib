package com.minecart.client.logic;

import com.minecart.client.events.ClientTickEvent;
import com.minecart.foundation.Circuit;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.foundation.World;
import com.minecart.registry.CircuitElementType;

import java.util.UUID;

/**
 * Client-side view of one electrical network: holds {@link ClientCircuit}s and links to {@link ClientLevel}.
 * Future: apply topology/variable updates from the server; packet decoding will wire into this type later.
 */
public class ClientWorld extends World {

    protected final ClientTickEvent.World preTick = new ClientTickEvent.World(ClientTickEvent.Phase.PRE, this);
    protected final ClientTickEvent.World postTick = new ClientTickEvent.World(ClientTickEvent.Phase.POST, this);

    public ClientWorld(ClientLevel level) {
        super(level);
    }

    /**
     * Client world with a fixed id (e.g. matching a server {@link World#getId()} for snapshots).
     */
    public ClientWorld(ClientLevel level, UUID worldId) {
        super(level, worldId);
    }

    @Override
    public ClientLevel getLevel() {
        return (ClientLevel) level;
    }

    @Override
    public void addCircuit(Circuit circuit) {
        super.addCircuit(circuit);
        if (circuit instanceof ClientCircuit cc) {
            cc.setWorld(this);
        }
    }

    /**
     * Per-frame client update for all circuits in this world.
     */
    public void tick() {
        post(preTick);
        for (Circuit c : getCircuits()) {
            if (c instanceof ClientCircuit cc) {
                cc.clientTick();
            }
        }
        post(postTick);
    }

    /**
     * Connects two nodes with an edge, like {@link com.minecart.logic.ServerWorld#connect} but with no simulation checks
     * and no {@link com.minecart.event.events.ElementEvent}s — for client mirror / replication only.
     */
    public <T extends CircuitEdge> T connect(CircuitElementType<T> type, CircuitNode node1, CircuitNode node2) {
        if (node1.getWorld() != this || node2.getWorld() != this) {
            throw new IllegalArgumentException("Nodes must belong to this ClientWorld");
        }
        T edge = type.create(this);
        edge.setWorld(this);
        edge.connect(node1, node2, false);
        node1.connectEdge(edge, false);
        node2.connectEdge(edge, false);
        Circuit c1 = node1.getCircuit();
        Circuit c2 = node2.getCircuit();
        if (c1 != c2) {
            c2.mergeInto(c1);
            removeCircuit(c2);
        }
        c1.addEdge(edge);
        return edge;
    }

    /**
     * Disconnects an edge and splits {@link Circuit}s; no {@link com.minecart.event.events.ElementEvent}s (replication apply).
     */
    public boolean disconnectWithoutRemoveEvent(CircuitEdge edge) {
        CircuitNode node1 = edge.getConnection(0);
        CircuitNode node2 = edge.getConnection(1);
        if (node1.getCircuit() != node2.getCircuit()) {
            return false;
        }
        ClientCircuit circuit = (ClientCircuit) node1.getCircuit();
        if (!node1.disconnect(edge, true) || !node2.disconnect(edge, true) || !edge.disconnect(true)) {
            return false;
        }
        ClientCircuit newCircuit = new ClientCircuit();
        newCircuit.setWorld(this);
        boolean createCircuit = circuit.seperate(node1, node2, edge, newCircuit);
        if (createCircuit) {
            this.circuits.add(newCircuit);
        }
        return true;
    }

    /**
     * Removes a node from the client mirror graph; no element events (replication apply).
     */
    public boolean destroy(CircuitNode node) {
        if (node.getWorld() != this) {
            throw new IllegalArgumentException("Node does not belong to this ClientWorld");
        }
        ClientCircuit circuit = (ClientCircuit) node.getCircuit();
        circuit.destroy(node);
        if (circuit.nodes().isEmpty()) {
            this.circuits.remove(circuit);
        }
        return true;
    }
}
