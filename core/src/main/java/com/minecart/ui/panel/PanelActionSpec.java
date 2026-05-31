package com.minecart.ui.panel;

import java.util.Objects;

/**
 * A command button on a panel. Actions are not saved as field values; the display/controller layer
 * dispatches them as commands (e.g. delete element, reset pivot).
 */
public final class PanelActionSpec {

    public static final PanelActionSpec DELETE = new PanelActionSpec("core:delete", "Delete Element");

    private final String key;
    private final String label;

    public PanelActionSpec(String key, String label) {
        this.key = Objects.requireNonNull(key, "key");
        this.label = Objects.requireNonNull(label, "label");
        if (key.isBlank()) {
            throw new IllegalArgumentException("Panel action key must be non-empty");
        }
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }
}
