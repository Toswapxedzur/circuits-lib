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
        T edge = type.create(this, false);
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
     * Drops {@code edge} from its circuit, detaches both endpoints, and clears its {@code start}/{@code end}.
     * Used when the server has told us — via a REMOVE delta — that this edge is gone.
     *
     * <p><b>Differs from {@link com.minecart.logic.ServerWorld#disconnectWithoutRemoveEvent} on purpose.</b>
     * On the server, disconnecting a wire may legitimately split the circuit into two and the seperate
     * pass moves stranded sub-graphs into a fresh circuit. Replication-side, we never run that pass:
     * any membership change is announced explicitly via a CircuitLifecycle insert plus an element
     * REBIND-CHANGE delta, so a local seperate would invent a client-only circuit UUID that never
     * matches the server's, leading to "REBIND: element not in source circuit" failures the next time
     * the server tells us the same element moved.
     *
     * <p>The hasComponent guard on {@link CircuitEdge#disconnect(boolean)} is also intentionally
     * ignored here — when this method runs, the server has already authorised the removal and the
     * client has no business second-guessing it.
     */
    public boolean disconnectWithoutRemoveEvent(CircuitEdge edge) {
        if (edge == null) {
            return false;
        }
        edge.setComponent(null); // bypass disconnect()'s hasComponent gate
        CircuitNode node1 = edge.getStart();
        CircuitNode node2 = edge.getEnd();
        Circuit circuit = edge.getCircuit();
        if (node1 != null) {
            node1.disconnect(edge, false);
        }
        if (node2 != null && node2 != node1) {
            node2.disconnect(edge, false);
        }
        edge.disconnect(false);
        if (circuit != null) {
            circuit.edges().remove(edge);
        }
        return true;
    }

    /**
     * Removes a node from the client mirror without cascading into incident edges (those arrive as
     * their own REMOVE deltas). The node is detached from any edge's connection set so a half-removed
     * frame doesn't render an edge pointing at a now-orphaned position.
     *
     * <p>Like {@link #disconnectWithoutRemoveEvent}, this skips the seperate-on-disconnect pass that
     * the server runs — the server is authoritative about circuit membership and will REBIND any
     * remaining elements via a separate delta when needed.
     */
    public boolean destroy(CircuitNode node) {
        if (node.getWorld() != this) {
            throw new IllegalArgumentException("Node does not belong to this ClientWorld");
        }
        ClientCircuit circuit = (ClientCircuit) node.getCircuit();
        if (circuit == null) {
            return false;
        }
        for (CircuitEdge edge : new java.util.ArrayList<>(node.getConnection())) {
            node.disconnect(edge, false);
        }
        circuit.nodes().remove(node);
        if (circuit.nodes().isEmpty() && circuit.edges().isEmpty() && circuit.components().isEmpty()) {
            this.circuits.remove(circuit);
        }
        return true;
    }
}
