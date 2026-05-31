package com.minecart.ui.panel.fields;

import com.minecart.ui.panel.PanelField;
import com.minecart.ui.panel.PanelFieldKey;

/**
 * Bounded numeric slider. Renders as a libGDX {@code Slider}. Snapshot value is a {@code Double}.
 *
 * <p>Unlike {@link NumberFieldSpec} (which is free-form and has no client-side bounds), a slider
 * is intrinsically bounded by the widget — the user physically cannot select a value outside
 * {@code [min, max]} — so {@link #min} and {@link #max} are part of the field spec rather than
 * being treated as soft validation hints. The server-side listener should still re-check the value
 * because a malicious or buggy client could send anything; the bounds here exist only to give the
 * widget something to render.
 */
public final class SliderSpec extends PanelField {

    private final double min;
    private final double max;
    private final double step;
    private final double initialValue;

    public SliderSpec(String key, String label, double min, double max, double step, double initialValue) {
        super(key, label);
        if (!(min < max)) {
            throw new IllegalArgumentException("min (" + min + ") must be less than max (" + max + ")");
        }
        if (!(step > 0.0)) {
            throw new IllegalArgumentException("step must be positive");
        }
        this.min = min;
        this.max = max;
        this.step = step;
        // Clamp so callers can't construct a spec whose rendered initial position would be outside
        // the bar; cheaper than burdening every caller with the clamp themselves.
        this.initialValue = Math.max(min, Math.min(max, initialValue));
    }

    public SliderSpec(PanelFieldKey<Double> key, String label, double min, double max, double step, double initialValue) {
        super(key, label);
        if (!(min < max)) {
            throw new IllegalArgumentException("min (" + min + ") must be less than max (" + max + ")");
        }
        if (!(step > 0.0)) {
            throw new IllegalArgumentException("step must be positive");
        }
        this.min = min;
        this.max = max;
        this.step = step;
        // Clamp so callers can't construct a spec whose rendered initial position would be outside
        // the bar; cheaper than burdening every caller with the clamp themselves.
        this.initialValue = Math.max(min, Math.min(max, initialValue));
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public double getStep() {
        return step;
    }

    public double getInitialValue() {
        return initialValue;
    }
}
