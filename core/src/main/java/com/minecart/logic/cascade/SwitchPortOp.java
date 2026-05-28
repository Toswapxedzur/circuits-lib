package com.minecart.logic.cascade;

import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerWorld;

/**
 * Replaces a {@link CircuitComponent}'s port at a given index with another node of the same registry
 * type. Wraps {@link CircuitComponent#switchPort(int, CircuitNode)} so the cascade engine can roll
 * it back if a later step fails.
 *
 * <p>Captures the previously-installed node on a successful {@link #apply} so {@link #undo} can put
 * it back. Type compatibility, multi-owner bookkeeping, and "is this a registered port" are all
 * checked by {@code CircuitComponent.switchPort} itself; this op just forwards.
 */
public final class SwitchPortOp implements CascadeOp {

    private final CircuitComponent component;
    private final int portIndex;
    private final CircuitNode newNode;
    private CircuitNode replaced;

    public SwitchPortOp(CircuitComponent component, int portIndex, CircuitNode newNode) {
        this.component = component;
        this.portIndex = portIndex;
        this.newNode = newNode;
    }

    @Override
    public boolean apply(ServerWorld world) {
        if (component == null || newNode == null) {
            return false;
        }
        CircuitNode current = component.getPort(portIndex);
        if (current == null) {
            return false;
        }
        if (current == newNode) {
            // Already in place; record null replaced so undo is a no-op.
            replaced = null;
            return true;
        }
        replaced = current;
        if (!component.switchPort(portIndex, newNode)) {
            replaced = null;
            return false;
        }
        return true;
    }

    @Override
    public void undo(ServerWorld world) {
        if (replaced == null) {
            return;
        }
        component.switchPort(portIndex, replaced);
        replaced = null;
    }

    public CircuitComponent getComponent() {
        return component;
    }

    public int getPortIndex() {
        return portIndex;
    }

    public CircuitNode getNewNode() {
        return newNode;
    }
}
