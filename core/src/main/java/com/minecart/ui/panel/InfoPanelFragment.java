package com.minecart.ui.panel;

import com.minecart.event.events.ElementInfoUpdateEvent;
import com.minecart.logic.CircuitElement;

/**
 * Cross-cutting panel contribution: a chunk of fields that should appear on the info panel of
 * <em>every</em> element of a given kind (node, edge, or component), independent of the element's
 * concrete {@link com.minecart.registry.CircuitElementType type-specific} definition.
 *
 * <h2>Why this exists</h2>
 * The earlier panel system was one-definition-per-type: a Resistor's panel was authored entirely
 * in {@code Resistor}'s static block. Position / rotation / lock fields apply to literally every
 * element kind though, and copy-pasting them into every concrete element's definition would be
 * fragile (drift between elements) and contrary to the design rule "panel fields for position /
 * rotation / lock are registered from within {@link com.minecart.logic.CircuitNode},
 * {@link com.minecart.logic.CircuitEdge}, {@link com.minecart.logic.CircuitComponent}".
 *
 * <p>A fragment is just a function {@code (element, builder) -> contribute rows to the builder}.
 * The registry composes the fragment outputs with the type-specific definition into one final
 * schema at panel-open time.
 *
 * <h2>Key namespacing</h2>
 * Fragment keys must use a namespace prefix (recommended: {@code core:<feature>.<sub>}) to avoid
 * collision with the type-specific definition's flat keys. The builder's uniqueness check still
 * enforces this at composition time so a stray duplicate fails loudly during panel build instead
 * of silently clobbering values in the snapshot.
 *
 * <h2>Save handling</h2>
 * Fragments that ship editable rows usually also register a {@link FragmentSaveHandler} via
 * {@link InfoPanelRegistry#registerFragmentSaveHandler}. The registry's
 * {@link InfoPanelRegistry#installLevelListener(com.minecart.foundation.Level)} dispatcher invokes
 * every matching fragment save handler before the type-specific handler, so element-side logic
 * sees the cross-cutting state already up to date.
 *
 * @param <T> upper bound of the element kind the fragment applies to ({@link com.minecart.logic.CircuitNode},
 *            {@link com.minecart.logic.CircuitEdge}, {@link com.minecart.logic.CircuitComponent}).
 */
@FunctionalInterface
public interface InfoPanelFragment<T extends CircuitElement> {

    /**
     * Adds rows to {@code builder} for {@code element}. May skip when the element shouldn't show
     * those rows in its current state (e.g. an internal port node hiding its position field).
     */
    void contribute(T element, InfoPanelSchema.Builder builder);

    /**
     * Server-side counterpart of a {@link InfoPanelFragment}: consumes the snapshot keys the
     * fragment authored, applies them to {@code element}. Fired by the same level listener that
     * dispatches the type-specific save handler, before the type-specific handler runs (so the
     * type-specific code can react to the new cross-cutting state).
     *
     * <p>Like {@link com.minecart.ui.panel.fields.NumberFieldSpec}, validation lives here on the
     * server side — invalid entries are silently dropped and the client re-syncs the old value
     * on the next mirror update.
     */
    @FunctionalInterface
    interface FragmentSaveHandler<T extends CircuitElement> {
        void apply(T element, com.minecart.ui.panel.PanelSnapshot snapshot, ElementInfoUpdateEvent event);
    }
}
