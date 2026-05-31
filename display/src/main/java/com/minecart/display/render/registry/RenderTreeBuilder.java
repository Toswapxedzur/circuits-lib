package com.minecart.display.render.registry;

import com.minecart.logic.CircuitElement;

import java.util.LinkedHashMap;
import java.util.Objects;

/** Mutable render composition builder used while walking a render type ancestry chain. */
public final class RenderTreeBuilder<T extends CircuitElement> {

    private final LinkedHashMap<RenderPartKey, RenderPart<? super T>> parts = new LinkedHashMap<>();

    public RenderTreeBuilder<T> add(RenderPartKey key, RenderPart<? super T> part) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(part, "part");
        if (parts.containsKey(key)) {
            throw new IllegalArgumentException("Duplicate render part: " + key.id());
        }
        parts.put(key, part);
        return this;
    }

    public RenderTreeBuilder<T> remove(RenderPartKey key) {
        parts.remove(Objects.requireNonNull(key, "key"));
        return this;
    }

    public RenderTreeBuilder<T> replace(RenderPartKey key, RenderPart<? super T> part) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(part, "part");
        LinkedHashMap<RenderPartKey, RenderPart<? super T>> rebuilt = new LinkedHashMap<>();
        boolean replaced = false;
        for (var entry : parts.entrySet()) {
            if (entry.getKey().equals(key)) {
                rebuilt.put(key, part);
                replaced = true;
            } else {
                rebuilt.put(entry.getKey(), entry.getValue());
            }
        }
        parts.clear();
        parts.putAll(rebuilt);
        if (!replaced) {
            parts.put(key, part);
        }
        return this;
    }

    public RenderTreeBuilder<T> moveBefore(RenderPartKey key, RenderPartKey anchor) {
        return move(key, anchor, true);
    }

    public RenderTreeBuilder<T> moveAfter(RenderPartKey key, RenderPartKey anchor) {
        return move(key, anchor, false);
    }

    private RenderTreeBuilder<T> move(RenderPartKey key, RenderPartKey anchor, boolean before) {
        if (key.equals(anchor) || !parts.containsKey(key) || !parts.containsKey(anchor)) {
            return this;
        }
        RenderPart<? super T> moved = parts.remove(key);
        LinkedHashMap<RenderPartKey, RenderPart<? super T>> rebuilt = new LinkedHashMap<>();
        for (var entry : parts.entrySet()) {
            if (before && entry.getKey().equals(anchor)) {
                rebuilt.put(key, moved);
            }
            rebuilt.put(entry.getKey(), entry.getValue());
            if (!before && entry.getKey().equals(anchor)) {
                rebuilt.put(key, moved);
            }
        }
        parts.clear();
        parts.putAll(rebuilt);
        return this;
    }

    RenderPlan<T> build() {
        java.util.ArrayList<RenderPart<? super T>> ordered = new java.util.ArrayList<>();
        ordered.addAll(parts.values());
        return new RenderPlan<T>(java.util.Collections.unmodifiableList(ordered));
    }
}
