package com.minecart.display.render.registry;

import java.util.Objects;

/** Stable identity for a render part so child renderers can remove/replace/reorder parent parts. */
public final class RenderPartKey {

    private final String id;

    public RenderPartKey(String id) {
        this.id = Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Render part key must be non-empty");
        }
    }

    public String id() {
        return id;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof RenderPartKey other && id.equals(other.id);
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
