package com.minecart.ui.panel.fields;

import com.minecart.ui.panel.PanelField;
import com.minecart.ui.panel.PanelFieldKey;

/**
 * Free-form numeric input (rendered as a libGDX {@code TextField} restricted to digits / minus /
 * dot). Snapshot value is a {@code Double}. The renderer is permitted to parse-best-effort: when
 * the user types nonsense (e.g. "abc") the snapshot carries {@link Double#NaN} or the field's
 * {@link #initialValue}, and the server-side listener decides whether to ignore the update — see
 * Q11 in the original design: no client-side validation, the server is the final arbiter.
 *
 * <p>No min/max here on purpose. Validation lives entirely on the server side via the
 * {@link com.minecart.event.events.ElementInfoUpdateEvent} listener, which has the full element
 * context to decide what "valid" means (e.g. resistance &gt; 0). The client just transmits whatever
 * the user typed.
 */
public final class NumberFieldSpec extends PanelField {

    private final double initialValue;

    public NumberFieldSpec(String key, String label, double initialValue) {
        super(key, label);
        this.initialValue = initialValue;
    }

    public NumberFieldSpec(PanelFieldKey<Double> key, String label, double initialValue) {
        super(key, label);
        this.initialValue = initialValue;
    }

    /** Seed value the input shows when the panel opens. */
    public double getInitialValue() {
        return initialValue;
    }
}
