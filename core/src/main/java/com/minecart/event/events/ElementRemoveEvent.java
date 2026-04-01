package com.minecart.event.events;

import com.minecart.logic.CircuitElement;
import com.minecart.logic.World;

/**
 * Fired before a {@link CircuitElement} is removed from a world's topology (edge disconnect, node destroy, etc.).
 * Listeners may call {@link #setCancel()} to veto the removal; the caller must then abort without mutating.
 */
public class ElementRemoveEvent extends CancellableEvent {

    private final World world;
    private final CircuitElement element;

    public ElementRemoveEvent(World world, CircuitElement element) {
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
