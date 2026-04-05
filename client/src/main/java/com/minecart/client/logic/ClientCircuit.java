package com.minecart.client.logic;

import com.minecart.client.events.ClientTickEvent;
import com.minecart.event.events.Event;
import com.minecart.foundation.Circuit;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.foundation.World;
import com.minecart.registry.CircuitElementType;
import com.minecart.serialization.tag.CompoundTag;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Client-side mirror of a circuit graph (nodes, edges, components) without server-side solving.
 * Structural helpers mirror {@link com.minecart.logic.ServerCircuit} / {@link com.minecart.logic.ServerWorld} but are
 * forcible (no simulation) and skip connectivity guarantees; {@link Circuit#seperate} is inherited for splitting.
 * Future: reconcile with server snapshots and interpolation; network payloads will be applied here later.
 */
public class ClientCircuit extends Circuit {

    protected final ClientTickEvent.Circuit preTick = new ClientTickEvent.Circuit(ClientTickEvent.Phase.PRE, this);
    protected final ClientTickEvent.Circuit postTick = new ClientTickEvent.Circuit(ClientTickEvent.Phase.POST, this);

    public ClientCircuit() {
        super(UUID.randomUUID());
    }

    public ClientCircuit(UUID id) {
        super(id);
    }

    @Override
    public ClientWorld getWorld() {
        World w = super.getWorld();
        if (w != null && !(w instanceof ClientWorld)) {
            throw new IllegalStateException("ClientCircuit is bound to a non-client world: " + w.getClass().getName());
        }
        return (ClientWorld) w;
    }

    @Override
    public void setWorld(World world) {
        if (world != null && !(world instanceof ClientWorld)) {
            throw new IllegalArgumentException("ClientCircuit requires ClientWorld");
        }
        super.setWorld(world);
    }

    public void setWorld(ClientWorld world) {
        super.setWorld(world);
    }

    @Override
    public void load(World world, CompoundTag tag) {
        if (world != null && !(world instanceof ClientWorld)) {
            throw new IllegalArgumentException("ClientCircuit requires ClientWorld for load");
        }
        super.load(world, tag);
    }

    /**
     * Connects two nodes with an edge via {@link ClientWorld#connect} (forcible; no simulation, no element events).
     */
    public <T extends CircuitEdge> T connect(CircuitElementType<T> type, CircuitNode node1, CircuitNode node2) {
        ClientWorld w = getWorld();
        if (w == null) {
            throw new IllegalStateException("ClientCircuit has no world");
        }
        return w.connect(type, node1, node2);
    }

    /**
     * Forcibly removes a node and incident edges from this mirror; drops an empty circuit from the world.
     */
    public void destroyNode(CircuitNode node) {
        if (node.getCircuit() != this) {
            throw new IllegalArgumentException("Node is not in this circuit");
        }
        ClientWorld w = getWorld();
        if (w == null) {
            throw new IllegalStateException("ClientCircuit has no world");
        }
        destroy(node);
        pruneIfEmpty(w);
    }

    /**
     * Removes a component's internal structure from this circuit mirror (no server events); drops an empty circuit from the world.
     */
    public void destroyComponent(CircuitComponent component) {
        if (component.getCircuit() != this) {
            throw new IllegalArgumentException("Component is not in this circuit");
        }
        ClientWorld w = getWorld();
        if (w == null) {
            throw new IllegalStateException("ClientCircuit has no world");
        }
        component.destroyForTopologyMirror(this);
        components.remove(component);
        pruneIfEmpty(w);
    }

    /**
     * Attaches a node to this circuit (and world). If the node was on another circuit in the same world, it is moved.
     */
    public void insertNode(CircuitNode node) {
        ClientWorld w = getWorld();
        if (w == null) {
            throw new IllegalStateException("ClientCircuit has no world");
        }
        if (node.getWorld() != null && node.getWorld() != w) {
            throw new IllegalArgumentException("Node belongs to another world");
        }
        node.setWorld(w);
        Circuit old = node.getCircuit();
        if (old != null && old != this) {
            old.nodes().remove(node);
            pruneIfEmpty(w, old);
        }
        addNode(node);
    }

    /**
     * Attaches a component to this circuit (and world). If it was on another circuit in the same world, it is moved.
     */
    public void insertComponent(CircuitComponent component) {
        ClientWorld w = getWorld();
        if (w == null) {
            throw new IllegalStateException("ClientCircuit has no world");
        }
        if (component.getWorld() != null && component.getWorld() != w) {
            throw new IllegalArgumentException("Component belongs to another world");
        }
        component.setWorld(w);
        Circuit old = component.getCircuit();
        if (old != null && old != this) {
            old.components().remove(component);
            pruneIfEmpty(w, old);
        }
        addComponent(component);
    }

    private void pruneIfEmpty(ClientWorld w) {
        pruneIfEmpty(w, this);
    }

    private static void pruneIfEmpty(ClientWorld w, Circuit c) {
        if (c.nodes().isEmpty() && c.edges().isEmpty() && c.components().isEmpty()) {
            w.removeCircuit(c);
        }
    }

    /**
     * Removes a node and incident edges from the client mirror (no server element events).
     */
    public boolean destroy(CircuitNode node) {
        ClientWorld w = getWorld();
        if (w == null) {
            throw new IllegalStateException("ClientCircuit has no world");
        }
        for (CircuitEdge edge : new ArrayList<>(node.getConnection())) {
            w.disconnectWithoutRemoveEvent(edge);
        }
        this.nodes.remove(node);
        return true;
    }

    @Override
    public boolean destroyNodeForTopologyMirror(CircuitNode node) {
        return destroy(node);
    }

    public boolean post(Event event) {
        return getWorld().post(event);
    }

    /**
     * Called between server updates for client-side smoothing or UI (no solver here).
     */
    public void clientTick() {
        post(preTick);
        onClientTick();
        post(postTick);
    }

    /** Per-frame client work between PRE and POST {@link ClientTickEvent.Circuit}. */
    protected void onClientTick() {
    }
}
