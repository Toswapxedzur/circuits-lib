package com.minecart.ui.panel.fields;

import com.minecart.ui.panel.PanelField;

/**
 * On/off toggle. Renders as a libGDX {@code CheckBox}. Snapshot value is a {@code Boolean}.
 */
public final class CheckboxSpec extends PanelField {

    private final boolean initialValue;

    public CheckboxSpec(String key, String label, boolean initialValue) {
        super(key, label);
        this.initialValue = initialValue;
    }

    public boolean getInitialValue() {
        return initialValue;
    }
}
