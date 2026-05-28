package com.minecart.ui.panel.fields;

import com.minecart.ui.panel.PanelField;

/**
 * Single-line text input. Renders as a libGDX {@code TextField}. Snapshot value is a non-null
 * {@code String} (empty string when the user clears the field).
 */
public final class TextFieldSpec extends PanelField {

    private final String initialValue;

    public TextFieldSpec(String key, String label, String initialValue) {
        super(key, label);
        this.initialValue = initialValue == null ? "" : initialValue;
    }

    /** Value the widget seeds with when the panel opens. Never null. */
    public String getInitialValue() {
        return initialValue;
    }
}
