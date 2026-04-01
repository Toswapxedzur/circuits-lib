package com.minecart.event.events;

import com.minecart.logic.CircuitElement;
import com.minecart.logic.World;

/**
 * Fired before a {@link CircuitElement} is committed into a world's topology (e.g. new node or edge).
 * Listeners may call {@link #setCancel()} to veto the insertion; the caller must then abort without mutating.
 */
public class ElementInsertEvent extends CancellableEvent {

    private final World world;
    private final CircuitElement element;

    public ElementInsertEvent(World world, CircuitElement element) {
        this.world = world;
        this.element = element;
    }

    public World getWorld() {
        return world;
    }

    public CircuitElement getElement() {
        return element;
    }
}
