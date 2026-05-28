package com.minecart.event.events;

import com.minecart.logic.CircuitElement;
import com.minecart.ui.panel.PanelSnapshot;

import java.util.Objects;

/**
 * Server-side event fired when a client sends an info-panel save (a {@code minecart.element_info_update_payload}).
 * The payload itself does nothing authoritative: the server handler resolves the element by id,
 * posts this event on the {@link com.minecart.foundation.Level}'s event bus, and lets listeners
 * apply whatever subset of the snapshot they understand.
 *
 * <p>Typical listener (registered in an element class's static initialiser, similar to how
 * {@link com.minecart.elements.edge.Resistor} registers its action handlers):
 *
 * <pre>{@code
 * level.register(ElementInfoUpdateEvent.class, evt -> {
 *     if (evt.getElement() instanceof Resistor r) {
 *         evt.getSnapshot().getDouble("resistance")
 *                          .filter(v -> v > 0)
 *                          .ifPresent(r::setResistance);
 *     }
 * });
 * }</pre>
 *
 * <p>Validation lives in the listener, not in the protocol layer: the listener has full element
 * context and can decide whether each snapshot field is acceptable. Rejecting an update is simply
 * "don't apply it" — the client's snapshot/delta sync will refresh its mirror back to the
 * unchanged server state on the next tick, which is precisely the "invalid data → server doesn't
 * change → client reopens panel and sees old data" flow we want.
 *
 * <p>Not cancellable: this event is purely a notification fan-out. Multiple listeners can each
 * apply a different subset of fields and they should not interfere with one another.
 */
public class ElementInfoUpdateEvent extends Event {

    private final CircuitElement element;
    private final PanelSnapshot snapshot;

    public ElementInfoUpdateEvent(CircuitElement element, PanelSnapshot snapshot) {
        this.element = Objects.requireNonNull(element, "element");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    /** The element the panel was opened on (already resolved by the handler). Never null. */
    public CircuitElement getElement() {
        return element;
    }

    /** The user-edited field values from the panel. Never null; may be empty. */
    public PanelSnapshot getSnapshot() {
        return snapshot;
    }
}
