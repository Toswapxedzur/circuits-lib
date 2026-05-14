package com.minecart.event.events;

import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;

/**
 * Fired when a {@link Circuit} is added to or removed from a {@link World}. Used by the server's replication
 * pipeline to tell clients about implicit circuit creation/destruction (e.g. {@code createNode} allocates a
 * fresh circuit, edge insertion may merge two circuits, edge removal may split one). Without these events,
 * the client would receive {@link com.minecart.logic.CircuitElement} deltas referencing a circuit id it has
 * never seen and the {@code CircuitElementHandler} would throw {@code "No circuit for id ..."}.
 *
 * <p>Not cancellable: the mutation has already been decided by the caller; listeners only observe.
 */
public class CircuitLifecycleEvent extends Event {

    private final World world;
    private final Circuit circuit;

    public CircuitLifecycleEvent(World world, Circuit circuit) {
        this.world = world;
        this.circuit = circuit;
    }

    public World getWorld() {
        return world;
    }

    public Circuit getCircuit() {
        return circuit;
    }

    /** Fired after a circuit becomes part of a world's topology. */
    public static class CircuitInsertEvent extends CircuitLifecycleEvent {
        public CircuitInsertEvent(World world, Circuit circuit) {
            super(world, circuit);
        }
    }

    /** Fired after a circuit is removed from a world's topology (merge, destroy-empty, or split rollback). */
    public static class CircuitRemoveEvent extends CircuitLifecycleEvent {
        public CircuitRemoveEvent(World world, Circuit circuit) {
            super(world, circuit);
        }
    }
}
