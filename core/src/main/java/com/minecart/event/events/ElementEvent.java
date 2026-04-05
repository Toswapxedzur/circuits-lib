package com.minecart.event.events;

import com.minecart.logic.CircuitElement;
import com.minecart.foundation.World;

public class ElementEvent extends CancellableEvent{
    private final World world;
    private final CircuitElement element;

    public ElementEvent(World world, CircuitElement element) {
        this.world = world;
        this.element = element;
    }

    public World getWorld() {
        return world;
    }

    public CircuitElement getElement() {
        return element;
    }

    /**
     * Fired before a {@link CircuitElement} is committed into a world's topology (e.g. new node or edge).
     * Listeners may call {@link #setCancel()} to veto the insertion; the caller must then abort without mutating.
     */
    public static class ElementInsertEvent extends ElementEvent {
        public ElementInsertEvent(World world, CircuitElement element) {
            super(world, element);
        }
    }

    /**
     * Fired before a {@link CircuitElement} is removed from a world's topology (edge disconnect, node destroy, etc.).
     * Listeners may call {@link #setCancel()} to veto the removal; the caller must then abort without mutating.
     */
    public static class ElementRemoveEvent extends ElementEvent {
        public ElementRemoveEvent(World world, CircuitElement element) {
            super(world, element);
        }
    }

}
