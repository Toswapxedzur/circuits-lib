package com.minecart.ui.panel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Mutable composition builder used while walking an {@link InfoPanelElementType} ancestry chain.
 * Children can remove or replace fields contributed by parents before the final immutable schema is
 * handed to the renderer.
 */
public final class PanelTreeBuilder<T extends com.minecart.logic.CircuitElement> {

    private final String title;
    private final LinkedHashMap<String, PanelField> fields = new LinkedHashMap<>();
    private final LinkedHashSet<String> removedKeys = new LinkedHashSet<>();
    private final List<PanelActionSpec> actions = new ArrayList<>();
    private final List<SaveBinding<T>> saveBindings = new ArrayList<>();

    public PanelTreeBuilder(String title) {
        this.title = Objects.requireNonNull(title, "title");
    }

    public PanelTreeBuilder<T> add(PanelField field) {
        Objects.requireNonNull(field, "field");
        String key = field.getKey();
        if (fields.containsKey(key)) {
            throw new IllegalArgumentException("Duplicate panel field key: " + key);
        }
        if (!removedKeys.contains(key)) {
            fields.put(key, field);
        }
        return this;
    }

    public PanelTreeBuilder<T> add(PanelField field, FieldSave<T> save) {
        add(field);
        save(field.fieldKey(), save);
        return this;
    }

    public PanelTreeBuilder<T> remove(PanelFieldKey<?> key) {
        return remove(key.id());
    }

    public PanelTreeBuilder<T> remove(String key) {
        Objects.requireNonNull(key, "key");
        fields.remove(key);
        removedKeys.add(key);
        return this;
    }

    public PanelTreeBuilder<T> replace(PanelFieldKey<?> key, PanelField field) {
        return replace(key.id(), field);
    }

    public PanelTreeBuilder<T> replace(String key, PanelField field) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(field, "field");
        LinkedHashMap<String, PanelField> rebuilt = new LinkedHashMap<>();
        boolean replaced = false;
        for (var entry : fields.entrySet()) {
            if (entry.getKey().equals(key)) {
                rebuilt.put(field.getKey(), field);
                replaced = true;
            } else {
                rebuilt.put(entry.getKey(), entry.getValue());
            }
        }
        fields.clear();
        fields.putAll(rebuilt);
        if (!replaced) {
            fields.put(field.getKey(), field);
        }
        removedKeys.remove(key);
        return this;
    }

    public PanelTreeBuilder<T> moveBefore(PanelFieldKey<?> key, PanelFieldKey<?> anchor) {
        return move(key.id(), anchor.id(), true);
    }

    public PanelTreeBuilder<T> moveAfter(PanelFieldKey<?> key, PanelFieldKey<?> anchor) {
        return move(key.id(), anchor.id(), false);
    }

    private PanelTreeBuilder<T> move(String key, String anchor, boolean before) {
        if (key.equals(anchor) || !fields.containsKey(key) || !fields.containsKey(anchor)) {
            return this;
        }
        PanelField moved = fields.remove(key);
        LinkedHashMap<String, PanelField> rebuilt = new LinkedHashMap<>();
        for (var entry : fields.entrySet()) {
            if (before && entry.getKey().equals(anchor)) {
                rebuilt.put(key, moved);
            }
            rebuilt.put(entry.getKey(), entry.getValue());
            if (!before && entry.getKey().equals(anchor)) {
                rebuilt.put(key, moved);
            }
        }
        fields.clear();
        fields.putAll(rebuilt);
        return this;
    }

    public PanelTreeBuilder<T> action(PanelActionSpec action) {
        Objects.requireNonNull(action, "action");
        actions.add(action);
        return this;
    }

    public PanelTreeBuilder<T> save(PanelFieldKey<?> key, FieldSave<T> save) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(save, "save");
        saveBindings.add(new SaveBinding<>(Set.of(key.id()), save));
        return this;
    }

    public PanelTreeBuilder<T> saveGroup(FieldSave<T> save, PanelFieldKey<?>... keys) {
        Objects.requireNonNull(save, "save");
        LinkedHashSet<String> deps = new LinkedHashSet<>();
        for (PanelFieldKey<?> key : keys) {
            deps.add(Objects.requireNonNull(key, "key").id());
        }
        saveBindings.add(new SaveBinding<>(deps, save));
        return this;
    }

    public PanelTreeBuilder<T> saveGroupStrings(FieldSave<T> save, String... keys) {
        Objects.requireNonNull(save, "save");
        LinkedHashSet<String> deps = new LinkedHashSet<>(Arrays.asList(keys));
        saveBindings.add(new SaveBinding<>(deps, save));
        return this;
    }

    public InfoPanelSchema buildSchema() {
        InfoPanelSchema.Builder builder = InfoPanelSchema.builder(title);
        for (PanelField field : fields.values()) {
            builder.add(field);
        }
        for (PanelActionSpec action : actions) {
            builder.addAction(action);
        }
        return builder.build();
    }

    List<SaveBinding<T>> saveBindings() {
        return saveBindings;
    }

    @FunctionalInterface
    public interface FieldSave<T extends com.minecart.logic.CircuitElement> {
        void apply(T element, PanelContext context);
    }

    record SaveBinding<T extends com.minecart.logic.CircuitElement>(
            Set<String> requiredKeys, FieldSave<T> save) {
        boolean activeIn(InfoPanelSchema schema) {
            Set<String> active = schema.fieldKeys();
            return requiredKeys.isEmpty() || active.containsAll(requiredKeys);
        }
    }
}
