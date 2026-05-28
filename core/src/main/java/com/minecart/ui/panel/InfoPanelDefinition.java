package com.minecart.ui.panel;

import com.minecart.logic.CircuitElement;

/**
 * Element-side description of an info panel. Implementations live next to (or inside) the element
 * class they describe, and are wired into {@link InfoPanelRegistry} at startup the same way
 * {@link com.minecart.registry.CircuitElementType}s wire into
 * {@link com.minecart.registry.CircuitElementRegistry}.
 *
 * <p>There is only one method, deliberately: {@link #build(CircuitElement)} returns a fresh
 * {@link InfoPanelSchema} seeded with the current state of {@code element}. The renderer then
 * shows the schema and, on Save, packages the user's edits into a {@link PanelSnapshot} that gets
 * shipped over the wire as a {@code minecart.element_info_update_payload}. The server doesn't ask
 * the definition anything — it just posts a
 * {@link com.minecart.event.events.ElementInfoUpdateEvent} and trusts listeners (which are typically
 * element-class side static blocks, mirroring the action-handler pattern in
 * {@link com.minecart.elements.edge.Resistor}'s static initialiser) to apply the change.
 *
 * @param <T> the concrete circuit-element subclass this definition describes; matches the
 *            {@link com.minecart.registry.CircuitElementType}'s payload type.
 */
public interface InfoPanelDefinition<T extends CircuitElement> {

    /**
     * Builds the schema to render for {@code element}. The returned schema's initial values must
     * reflect the element's current state so the panel opens "as the element is", not at zero.
     */
    InfoPanelSchema build(T element);
}
