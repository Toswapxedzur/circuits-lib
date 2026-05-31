package com.minecart.display.render.registry;

import com.minecart.logic.CircuitElement;

import java.util.Objects;

/**
 * Typed node in the display renderer inheritance tree. Rendering inheritance is intentionally
 * independent from Java inheritance so a future element can choose whatever visual ancestry fits.
 */
public final class RenderElementType<T extends CircuitElement> {

    private final String id;
    private final Class<T> elementClass;
    private final RenderElementType<? super T> parent;

    public RenderElementType(String id, Class<T> elementClass, RenderElementType<? super T> parent) {
        this.id = Objects.requireNonNull(id, "id");
        this.elementClass = Objects.requireNonNull(elementClass, "elementClass");
        this.parent = parent;
        if (id.isBlank()) {
            throw new IllegalArgumentException("Render type id must be non-empty");
        }
    }

    public String id() {
        return id;
    }

    public Class<T> elementClass() {
        return elementClass;
    }

    public RenderElementType<? super T> parent() {
        return parent;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof RenderElementType<?> other && id.equals(other.id);
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
