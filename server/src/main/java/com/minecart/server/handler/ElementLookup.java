package com.minecart.server.handler;

import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;

import java.util.UUID;

/**
 * Shared cross-circuit lookup helpers for the server-side payload handlers. Previously every handler
 * carried its own private {@code findNode}/{@code findElement}/{@code findEdge}/{@code findComponent}
 * linear-scan copy; those are consolidated here so there is a single scan implementation to reason about.
 *
 * <p>All methods return {@code null} for a {@code null} id or when no match exists in the world, matching
 * the "silent no-op on unknown id" contract the handlers rely on.
 */
public final class ElementLookup {

    private ElementLookup() {}

    /** Finds a node with {@code id} in any circuit of {@code world}, or {@code null}. */
    public static CircuitNode findNode(World world, UUID id) {
        return world.findNode(id);
    }

    /** Finds an element (node, edge, or component) with {@code id} in any circuit of {@code world}, or {@code null}. */
    public static CircuitElement findElement(World world, UUID id) {
        return world.findElement(id);
    }

    /** Finds an edge with {@code id} in any circuit of {@code world}, or {@code null}. */
    public static CircuitEdge findEdge(World world, UUID id) {
        return world.findEdge(id);
    }

    /** Finds a component with {@code id} in any circuit of {@code world}, or {@code null}. */
    public static CircuitComponent findComponent(World world, UUID id) {
        if (id == null) {
            return null;
        }
        for (Circuit circuit : world.getCircuits()) {
            for (CircuitComponent c : circuit.components()) {
                if (id.equals(c.getId())) {
                    return c;
                }
            }
        }
        return null;
    }
}
