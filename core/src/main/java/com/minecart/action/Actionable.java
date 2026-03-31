package com.minecart.action;

import com.minecart.logic.Circuit;
import com.minecart.logic.CircuitElement;
import com.minecart.registry.CircuitElementRegistry;
import com.minecart.registry.CircuitElementType;

import java.util.UUID;

/**
 * Pairs an element with an action; {@link Runnable} dispatches through the element's {@link CircuitElementType}.
 * Suitable for {@link com.minecart.logic.ServerLevel#submit(Runnable)}.
 */
public record Actionable(CircuitElement element, Action action) implements Runnable {

    /**
     * Resolves an element by {@code elementId} in {@code circuit} and builds an {@link Actionable}.
     * Used server-side to materialize a decoded payload into a submit-ready {@link Runnable}.
     *
     * @throws IllegalArgumentException if no element with {@code elementId} exists in the circuit
     */
    public static Actionable fromPayload(Circuit circuit, UUID elementId, Action action) {
        CircuitElement el = circuit.findElement(elementId);
        if (el == null) {
            throw new IllegalArgumentException("No element found for id: " + elementId);
        }
        return new Actionable(el, action);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void run() {
        CircuitElementType<?> type = CircuitElementRegistry.getType(element.getRegistryTypeId());
        ((CircuitElementType<CircuitElement>) type).perform(element, action);
    }
}
