package com.minecart.ui.panel;

import java.util.Objects;

/**
 * Base type for a single row in an {@link InfoPanelSchema}. Subclasses describe a concrete widget
 * kind (text, number, dropdown, etc.) — the {@code :display} module is responsible for translating
 * each subclass into a Scene2D actor.
 *
 * <p>Fields are pure data: they do not own a libGDX widget, do not hold mutable user-edited state,
 * and can therefore be cached, copied, or serialised freely. The user's actual edits live entirely
 * in the rendered widget and are collected into a {@link PanelSnapshot} when the user clicks Save.
 *
 * <p>{@link #key} is the stable identifier used in the snapshot; {@link #label} is the human-readable
 * caption shown next to the widget. Keys must be unique within a single {@link InfoPanelSchema} —
 * if two fields share a key, the second one's value silently overwrites the first in the snapshot,
 * which is rarely what anyone wants. {@link InfoPanelSchema.Builder} rejects duplicate keys at
 * build time so this can't happen by accident.
 */
public abstract class PanelField {

    private final String key;
    private final String label;

    protected PanelField(String key, String label) {
        this.key = Objects.requireNonNull(key, "key");
        this.label = Objects.requireNonNull(label, "label");
        if (key.isEmpty()) {
            throw new IllegalArgumentException("key must be non-empty");
        }
    }

    /** Stable identifier used both as the snapshot map key and as the panel-field uniqueness key. */
    public final String getKey() {
        return key;
    }

    /** Human-readable label shown next to the widget. */
    public final String getLabel() {
        return label;
    }
}
