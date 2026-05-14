package com.minecart.event.events;

import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.logic.CircuitElement;

/**
 * Fired when a {@link CircuitElement}'s {@link CircuitElement#getCircuit() owning circuit} silently changes
 * via {@link Circuit#mergeInto(Circuit)} (edge insertion fuses two circuits) or
 * {@link Circuit#seperate(com.minecart.logic.CircuitNode, com.minecart.logic.CircuitNode, com.minecart.logic.CircuitEdge, Circuit)
 * Circuit.seperate} (edge removal cleaves one into two). These structural moves don't go through the
 * insert/remove pipeline, so the replication listener wouldn't otherwise know that the element has shifted
 * circuits — and the client mirror would diverge.
 *
 * <p>Server-side replication wires this event to emit a {@code CHANGE} op carrying
 * {@code sourceCircuitId = oldCircuit.getId()} so the client can move the element between its circuit
 * mirrors before any subsequent op references it.
 *
 * <p>Not cancellable: the rebind has already happened on the authoritative side; listeners only observe.
 */
public class ElementCircuitChangedEvent extends Event {

    private final World world;
    private final CircuitElement element;
    private final Circuit oldCircuit;
    private final Circuit newCircuit;

    public ElementCircuitChangedEvent(World world, CircuitElement element, Circuit oldCircuit, Circuit newCircuit) {
        this.world = world;
        this.element = element;
        this.oldCircuit = oldCircuit;
        this.newCircuit = newCircuit;
    }

    public World getWorld() {
        return world;
    }

    public CircuitElement getElement() {
        return element;
    }

    public Circuit getOldCircuit() {
        return oldCircuit;
    }

    public Circuit getNewCircuit() {
        return newCircuit;
    }
}
