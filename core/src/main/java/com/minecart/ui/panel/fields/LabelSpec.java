package com.minecart.ui.panel.fields;

import com.minecart.ui.panel.PanelField;
import com.minecart.ui.panel.PanelFieldKey;

import java.util.Objects;

/**
 * Read-only display row. Useful for showing an element's UUID, registry type, or other diagnostics
 * inside the panel without offering an input. The renderer should not produce a snapshot entry for
 * this kind of field — its {@link #getKey() key} is still required to keep the schema validator
 * simple, but the snapshot omits the entry entirely so the server doesn't waste bytes echoing
 * read-only data back as a no-op update.
 */
public final class LabelSpec extends PanelField {

    private final String value;

    public LabelSpec(String key, String label, String value) {
        super(key, label);
        this.value = Objects.requireNonNull(value, "value");
    }

    public LabelSpec(PanelFieldKey<String> key, String label, String value) {
        super(key, label);
        this.value = Objects.requireNonNull(value, "value");
    }

    public String getValue() {
        return value;
    }
}
