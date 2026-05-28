package com.minecart.ui.panel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Description of the rows that should appear in an info panel.
 *
 * <p>Schemas are pure data: an {@link InfoPanelDefinition#build(com.minecart.logic.CircuitElement)
 * build(element)} call snapshots the element's current state into the field initial values, so the
 * same schema instance is safe to hand to the renderer without further coordination. The renderer
 * iterates {@link #getFields()} in order and produces one row per field.
 */
public final class InfoPanelSchema {

    private final String title;
    private final List<PanelField> fields;

    private InfoPanelSchema(String title, List<PanelField> fields) {
        this.title = title;
        this.fields = fields;
    }

    /** Title displayed at the top of the panel window. */
    public String getTitle() {
        return title;
    }

    /** Fields in display order. Immutable. */
    public List<PanelField> getFields() {
        return fields;
    }

    public static Builder builder(String title) {
        return new Builder(title);
    }

    /**
     * Accumulates fields and enforces key-uniqueness at build time so a stray duplicate doesn't
     * silently clobber values in the snapshot. Not thread-safe; build the schema on one thread.
     */
    public static final class Builder {

        private final String title;
        private final List<PanelField> fields = new ArrayList<>();
        private final Set<String> keys = new HashSet<>();

        private Builder(String title) {
            this.title = Objects.requireNonNull(title, "title");
        }

        public Builder add(PanelField field) {
            Objects.requireNonNull(field, "field");
            if (!keys.add(field.getKey())) {
                throw new IllegalArgumentException("Duplicate panel field key: " + field.getKey());
            }
            fields.add(field);
            return this;
        }

        public InfoPanelSchema build() {
            return new InfoPanelSchema(title, Collections.unmodifiableList(new ArrayList<>(fields)));
        }
    }
}
