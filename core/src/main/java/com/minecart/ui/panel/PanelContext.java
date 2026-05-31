package com.minecart.ui.panel;

import com.minecart.event.events.ElementInfoUpdateEvent;
import com.minecart.foundation.Level;
import com.minecart.foundation.World;
import com.minecart.logic.CircuitElement;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Shared state for applying a panel save. Field and group save handlers read parsed values from
 * the same snapshot, check which keys survived final schema composition, and queue changed
 * elements for one notification flush.
 */
public final class PanelContext {

    private final CircuitElement element;
    private final PanelSnapshot snapshot;
    private final InfoPanelSchema schema;
    private final ElementInfoUpdateEvent event;
    private final Set<String> activeKeys;
    private final Set<CircuitElement> changed = new LinkedHashSet<>();

    public PanelContext(CircuitElement element, PanelSnapshot snapshot,
                        InfoPanelSchema schema, ElementInfoUpdateEvent event) {
        this.element = Objects.requireNonNull(element, "element");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.event = Objects.requireNonNull(event, "event");
        this.activeKeys = schema.fieldKeys();
    }

    public CircuitElement element() {
        return element;
    }

    public PanelSnapshot snapshot() {
        return snapshot;
    }

    public InfoPanelSchema schema() {
        return schema;
    }

    public ElementInfoUpdateEvent event() {
        return event;
    }

    public World world() {
        return element.getWorld();
    }

    public Level level() {
        World world = world();
        return world != null ? world.getLevel() : null;
    }

    public boolean isActive(PanelFieldKey<?> key) {
        return key != null && activeKeys.contains(key.id());
    }

    public boolean isActive(String key) {
        return key != null && activeKeys.contains(key);
    }

    public <T> Optional<T> value(PanelFieldKey<T> key) {
        if (!isActive(key)) {
            return Optional.empty();
        }
        Object value = snapshot.asMap().get(key.id());
        if (value == null) {
            return Optional.empty();
        }
        if (key.valueType() == Double.class && value instanceof Number n) {
            return Optional.of(key.valueType().cast(n.doubleValue()));
        }
        if (key.valueType() == Long.class && value instanceof Number n) {
            return Optional.of(key.valueType().cast(n.longValue()));
        }
        if (key.valueType().isInstance(value)) {
            return Optional.of(key.valueType().cast(value));
        }
        return Optional.empty();
    }

    public Optional<Double> doubleValue(PanelFieldKey<Double> key) {
        return value(key);
    }

    public Optional<Boolean> booleanValue(PanelFieldKey<Boolean> key) {
        return value(key);
    }

    public Optional<String> stringValue(PanelFieldKey<String> key) {
        return value(key);
    }

    public void markChanged(CircuitElement changedElement) {
        if (changedElement != null) {
            changed.add(changedElement);
        }
    }

    public void flushNotifications() {
        Level level = level();
        if (level == null) {
            return;
        }
        for (CircuitElement changedElement : changed) {
            level.notifyElementChanged(changedElement);
        }
        changed.clear();
    }
}
