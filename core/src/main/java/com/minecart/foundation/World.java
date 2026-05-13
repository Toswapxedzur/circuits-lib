package com.minecart.foundation;

import com.minecart.event.events.Event;
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

    public boolean post(Event event) {
        return level.post(event);
    }
}
