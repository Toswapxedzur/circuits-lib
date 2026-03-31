package com.minecart.logic;

import com.minecart.event.events.Event;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Holds the circuits belonging to one electrical network and links to its {@link Level}.
 * {@link ServerWorld} adds ticking, element creation, and short-circuit handling.
 */
public class World {

    protected final Level level;

    protected final Set<Circuit> circuits = new LinkedHashSet<>();

    public World(Level level) {
        this.level = level;
    }

    public Level getLevel() {
        return level;
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

    public boolean post(Event event) {
        return level.post(event);
    }
}
