package com.minecart.ui.panel.fields;

import com.minecart.ui.panel.PanelField;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Single-select dropdown. Renders as a libGDX {@code SelectBox<String>}. Snapshot value is the
 * selected option {@code String} — never an index, so the wire format is stable across reorderings
 * of the option list.
 */
public final class DropdownSpec extends PanelField {

    private final List<String> options;
    private final String initialValue;

    public DropdownSpec(String key, String label, List<String> options, String initialValue) {
        super(key, label);
        Objects.requireNonNull(options, "options");
        if (options.isEmpty()) {
            throw new IllegalArgumentException("dropdown must have at least one option");
        }
        this.options = List.copyOf(options);
        // Default to the first option if the caller passes null or something that isn't in the list,
        // so the rendered widget is always in a valid state.
        this.initialValue = (initialValue != null && options.contains(initialValue))
                ? initialValue
                : options.get(0);
    }

    /** Immutable view of the option list, in display order. */
    public List<String> getOptions() {
        return Collections.unmodifiableList(options);
    }

    /** The option pre-selected when the panel opens. Always one of {@link #getOptions()}. */
    public String getInitialValue() {
        return initialValue;
    }
}
