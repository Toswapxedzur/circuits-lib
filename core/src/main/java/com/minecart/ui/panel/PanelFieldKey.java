package com.minecart.ui.panel;

import java.util.Objects;

/**
 * Typed identity for a panel field. The {@link #id()} remains the stable protocol/debug key, while
 * normal Java code can pass this object instead of raw strings when adding, replacing, removing, or
 * reading fields.
 */
public final class PanelFieldKey<T> {

    private final String id;
    private final Class<T> valueType;

    private PanelFieldKey(String id, Class<T> valueType) {
        this.id = Objects.requireNonNull(id, "id");
        this.valueType = Objects.requireNonNull(valueType, "valueType");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Panel field key id must be non-empty");
        }
    }

    public static PanelFieldKey<String> stringKey(String id) {
        return new PanelFieldKey<>(id, String.class);
    }

    public static PanelFieldKey<Double> doubleKey(String id) {
        return new PanelFieldKey<>(id, Double.class);
    }

    public static PanelFieldKey<Boolean> booleanKey(String id) {
        return new PanelFieldKey<>(id, Boolean.class);
    }

    public static PanelFieldKey<Long> longKey(String id) {
        return new PanelFieldKey<>(id, Long.class);
    }

    public String id() {
        return id;
    }

    public Class<T> valueType() {
        return valueType;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof PanelFieldKey<?> other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id;
    }
}
