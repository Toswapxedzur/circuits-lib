package com.minecart.ui.panel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable record of the values the user had in each input field when they clicked Save. The
 * client builds one of these from the live widget state, packages it into the panel-save payload,
 * and the server hands it (rehydrated) to the {@link com.minecart.event.events.ElementInfoUpdateEvent}
 * listener.
 *
 * <p>Storage is {@code Map<String, Object>} internally but the public API exposes typed accessors
 * ({@link #getDouble}, {@link #getString}, etc.) so callers don't have to do unchecked casts. Each
 * accessor returns an {@link Optional} so missing fields and wrong-type fields are uniformly
 * expressible — a listener that needs "resistance as a double" calls {@code getDouble("resistance")}
 * and decides what to do when it's empty.
 *
 * <p>Snapshots are intended to round-trip through the protocol layer as a
 * {@link com.minecart.serialization.tag.CompoundTag} (one entry per key, value tag chosen by type).
 * Read-only {@link com.minecart.ui.panel.fields.LabelSpec} fields are excluded from the snapshot
 * by convention; if a key appears in the schema but is missing here, it was a label.
 */
public final class PanelSnapshot {

    private final Map<String, Object> values;

    private PanelSnapshot(Map<String, Object> values) {
        this.values = values;
    }

    /** Number of value entries actually present. Excludes read-only label rows. */
    public int size() {
        return values.size();
    }

    public Map<String, Object> asMap() {
        return values;
    }

    public Optional<String> getString(String key) {
        Object v = values.get(key);
        return v instanceof String s ? Optional.of(s) : Optional.empty();
    }

    public Optional<Double> getDouble(String key) {
        Object v = values.get(key);
        // Accept any Number so an int-typed slider value or a long doesn't trip the listener up.
        if (v instanceof Number n) {
            return Optional.of(n.doubleValue());
        }
        return Optional.empty();
    }

    public Optional<Long> getLong(String key) {
        Object v = values.get(key);
        if (v instanceof Number n) {
            return Optional.of(n.longValue());
        }
        return Optional.empty();
    }

    public Optional<Boolean> getBoolean(String key) {
        Object v = values.get(key);
        return v instanceof Boolean b ? Optional.of(b) : Optional.empty();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Insertion-ordered builder. Order is preserved by the underlying {@link LinkedHashMap} so the
     * tag-serialised form is deterministic for testing.
     */
    public static final class Builder {

        private final Map<String, Object> values = new LinkedHashMap<>();

        private Builder() {}

        public Builder put(String key, String value) {
            return putRaw(key, Objects.requireNonNull(value, "value"));
        }

        public Builder put(String key, double value) {
            return putRaw(key, value);
        }

        public Builder put(String key, long value) {
            return putRaw(key, value);
        }

        public Builder put(String key, boolean value) {
            return putRaw(key, value);
        }

        /**
         * Tag-layer deserialiser path: inserts a value of an already-coerced type without re-checking
         * via the public per-type overloads. Internal callers (the protocol decode side) use this so
         * the {@code Object} type flows through unchanged from CompoundTag to snapshot to listener.
         */
        public Builder putRaw(String key, Object value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            values.put(key, value);
            return this;
        }

        public PanelSnapshot build() {
            return new PanelSnapshot(Collections.unmodifiableMap(new LinkedHashMap<>(values)));
        }
    }
}
