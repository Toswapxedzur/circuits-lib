package com.minecart.action;

import com.minecart.logic.Circuit;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.Level;
import com.minecart.logic.World;
import com.minecart.registry.CircuitElementRegistry;
import com.minecart.registry.CircuitElementType;

import java.util.UUID;

/**
 * Pairs an element with an action; {@link Runnable} dispatches through the element's {@link CircuitElementType}.
 * Suitable for {@link com.minecart.logic.ServerLevel#submit(Runnable)}.
 */
public record Actionable(CircuitElement element, Action action) implements Runnable {

    /**
     * Resolves world → circuit → element from a full payload.
     * If {@code worldId} is {@code null}, {@link Level#findCircuit(UUID)} is used so a single circuit can be synced
     * without embedding a world id (circuit ids must be unique across the level, or the first match wins).
     *
     * @throws IllegalArgumentException if ids do not resolve to an element
     */
    public static Actionable fromPayload(Level level, UUID worldId, UUID circuitId, UUID elementId, Action action) {
        if (level == null) {
            throw new IllegalArgumentException("level is null");
        }
        if (circuitId == null || elementId == null || action == null) {
            throw new IllegalArgumentException("circuitId, elementId, and action must be non-null");
        }
        Circuit circuit;
        if (worldId != null) {
            World world = level.findWorld(worldId);
            if (world == null) {
                throw new IllegalArgumentException("No world for id: " + worldId);
            }
            circuit = world.findCircuit(circuitId);
            if (circuit == null) {
                throw new IllegalArgumentException("No circuit for id: " + circuitId + " in world " + worldId);
            }
        } else {
            circuit = level.findCircuit(circuitId);
            if (circuit == null) {
                throw new IllegalArgumentException("No circuit for id: " + circuitId + " in level");
            }
        }
        return fromPayload(circuit, elementId, action);
    }

    /**
     * Resolves circuit → element when the {@link World} is already known (e.g. connection-scoped).
     */
    public static Actionable fromPayload(World world, UUID circuitId, UUID elementId, Action action) {
        if (world == null) {
            throw new IllegalArgumentException("world is null");
        }
        if (circuitId == null || elementId == null || action == null) {
            throw new IllegalArgumentException("circuitId, elementId, and action must be non-null");
        }
        Circuit circuit = world.findCircuit(circuitId);
        if (circuit == null) {
            throw new IllegalArgumentException("No circuit for id: " + circuitId + " in world " + world.getId());
        }
        return fromPayload(circuit, elementId, action);
    }

    /**
     * Resolves an element by {@code elementId} in {@code circuit}.
     *
     * @throws IllegalArgumentException if no element with {@code elementId} exists in the circuit
     */
    public static Actionable fromPayload(Circuit circuit, UUID elementId, Action action) {
        if (circuit == null) {
            throw new IllegalArgumentException("circuit is null");
        }
        if (elementId == null || action == null) {
            throw new IllegalArgumentException("elementId and action must be non-null");
        }
        CircuitElement el = circuit.findElement(elementId);
        if (el == null) {
            throw new IllegalArgumentException("No element found for id: " + elementId);
        }
        return new Actionable(el, action);
    }

    @Override
    public void run() {
        CircuitElementType<?> type = CircuitElementRegistry.getType(element.getRegistryTypeId());
        ((CircuitElementType<CircuitElement>) type).perform(element, action);
    }
}
