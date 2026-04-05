package com.minecart.client.network;

import com.minecart.logic.CircuitComponent;
import com.minecart.serialization.tag.CompoundTag;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Optional custom sync readers/writers for {@link CircuitComponent} subclasses when mirroring state to the client.
 * When no handler is registered for a component's class, {@link CircuitComponent#save(CompoundTag)} /
 * {@link CircuitComponent#load(CompoundTag)} are used.
 */
public final class SyncRegistry {

    private static final Map<Class<? extends CircuitComponent>, SyncHandler<?>> REGISTRY = new HashMap<>();

    private static volatile boolean handlersLoaded;

    private static void ensureHandlersRegistered() {
        if (!handlersLoaded) {
            synchronized (SyncRegistry.class) {
                if (!handlersLoaded) {
                    try {
                        Class.forName(SyncHandlers.class.getName());
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                    handlersLoaded = true;
                }
            }
        }
    }

    private SyncRegistry() {
    }

    /**
     * Registers custom sync logic for a specific component class (exact {@link Class} match on
     * {@link Object#getClass()} during read/write).
     */
    public static <T extends CircuitComponent> void register(
            Class<T> componentClass,
            BiConsumer<T, CompoundTag> customWriter,
            BiConsumer<T, CompoundTag> customReader) {
        register(componentClass, new SyncHandler<>(customWriter, customReader));
    }

    /**
     * Registers a composed {@link SyncHandler} for a component class.
     */
    public static <T extends CircuitComponent> void register(Class<T> componentClass, SyncHandler<T> handler) {
        Objects.requireNonNull(componentClass, "componentClass");
        Objects.requireNonNull(handler, "handler");
        REGISTRY.put(componentClass, handler);
    }

    /**
     * Writes component data to {@code tag} for client sync (network-friendly subset or full save).
     */
    @SuppressWarnings("unchecked")
    public static void writeSyncData(CircuitComponent component, CompoundTag tag) {
        ensureHandlersRegistered();
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(tag, "tag");
        SyncHandler<CircuitComponent> handler =
                (SyncHandler<CircuitComponent>) REGISTRY.get(component.getClass());
        if (handler != null) {
            handler.writer().accept(component, tag);
        } else {
            component.save(tag);
        }
    }

    /**
     * Reads synced data from {@code tag} into {@code component}.
     */
    @SuppressWarnings("unchecked")
    public static void readSyncData(CircuitComponent component, CompoundTag tag) {
        ensureHandlersRegistered();
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(tag, "tag");
        SyncHandler<CircuitComponent> handler =
                (SyncHandler<CircuitComponent>) REGISTRY.get(component.getClass());
        if (handler != null) {
            handler.reader().accept(component, tag);
        } else {
            component.load(tag);
        }
    }
}
