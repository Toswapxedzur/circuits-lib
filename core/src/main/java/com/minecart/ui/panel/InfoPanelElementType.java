package com.minecart.ui.panel;

import com.minecart.logic.CircuitElement;

import java.util.Objects;

/**
 * Typed node in the panel-inheritance tree. This is deliberately separate from Java inheritance and
 * from {@link com.minecart.registry.CircuitElementType}: a concrete element type can choose the UI
 * panel ancestry that best matches its editing model.
 */
public final class InfoPanelElementType<T extends CircuitElement> {

    private final String id;
    private final Class<T> elementClass;
    private final InfoPanelElementType<? super T> parent;

    public InfoPanelElementType(String id, Class<T> elementClass, InfoPanelElementType<? super T> parent) {
        this.id = Objects.requireNonNull(id, "id");
        this.elementClass = Objects.requireNonNull(elementClass, "elementClass");
        this.parent = parent;
        if (id.isBlank()) {
            throw new IllegalArgumentException("Info panel type id must be non-empty");
        }
    }

    public String id() {
        return id;
    }

    public Class<T> elementClass() {
        return elementClass;
    }

    public InfoPanelElementType<? super T> parent() {
        return parent;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof InfoPanelElementType<?> other && id.equals(other.id);
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
