package com.minecart.foundation;

import com.minecart.event.events.Event;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerCircuit;
import com.minecart.logic.ServerWorld;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Holds the circuits belonging to one electrical network and links to its {@link Level}.
 * {@link ServerWorld} adds ticking, element creation, and short-circuit handling.
 */
public class World {

    protected final Level level;

    /** Stable id for this electrical network (e.g. client/server routing, {@link com.minecart.client.ActionPayload}). */
    protected final UUID id;

    /**
     * Human-readable label shown in the editor's world dropdown. Optional metadata: never used for routing
     * or simulation. Persisted alongside {@link #id} by {@link com.minecart.server.persistence.WorldStorage}
     * and synced to clients via {@link com.minecart.protocol.payload.server.WorldLifecyclePayload}.
     */
    protected String name;

    protected final Set<Circuit> circuits = new LinkedHashSet<>();

    public World(Level level) {
        this(level, null);
    }

    /**
     * @param id stable network id; if {@code null}, a random id is assigned (normal construction).
     */
    protected World(Level level, UUID id) {
        this.level = level;
        this.id = id != null ? id : UUID.randomUUID();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Level getLevel() {
        return level;
    }

    /** Finds a circuit in this world by {@link Circuit#getId()}. */
    public Circuit findCircuit(UUID circuitId) {
        if (circuitId == null) {
            return null;
        }
        for (Circuit c : circuits) {
            if (circuitId.equals(c.getId())) {
                return c;
            }
        }
        return null;
    }

    public double getTickRate() {
        return level.getTickRate();
    }

    public Set<Circuit> getCircuits() {
        return circuits;
    }

    /**
     * Registers a circuit with this world. Subclasses may attach simulation state (e.g. {@link ServerCircuit#setWorld}).
     */
    public void addCircuit(Circuit circuit) {
        circuits.add(circuit);
    }

    /** Removes a circuit from this world without destroying element references (caller replaces via snapshot). */
    public boolean removeCircuit(Circuit circuit) {
        return circuits.remove(circuit);
    }

    /**
     * Walks every {@link Circuit} in this world looking for a node with the given id. Useful when an
     * element's owning circuit isn't known up-front — e.g. a {@link com.minecart.logic.CircuitComponent}
     * loading its internal port list, where the ports may have been merged into a different circuit than
     * the component itself.
     */
    public CircuitNode findNode(UUID id) {
        if (id == null) {
            return null;
        }
        for (Circuit c : circuits) {
            CircuitNode n = c.findNode(id);
            if (n != null) return n;
        }
        return null;
    }

    /** Cross-circuit edge lookup; see {@link #findNode(UUID)}. */
    public CircuitEdge findEdge(UUID id) {
        if (id == null) {
            return null;
        }
        for (Circuit c : circuits) {
            CircuitEdge e = c.findEdge(id);
            if (e != null) return e;
        }
        return null;
    }

    /** Cross-circuit element lookup (node, edge, or component). */
    public CircuitElement findElement(UUID id) {
        if (id == null) {
            return null;
        }
        for (Circuit c : circuits) {
            CircuitElement el = c.findElement(id);
            if (el != null) return el;
        }
        return null;
    }

    public boolean post(Event event) {
        return level.post(event);
    }
}
