package com.minecart.protocol.sync;

import com.minecart.logic.CircuitElement;
import com.minecart.serialization.tag.CompoundTag;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Optional custom sync readers/writers for concrete {@link CircuitElement} subclasses when mirroring state to the client.
 * When no handler is registered for an element's runtime class, {@link CircuitElement#save(CompoundTag)} /
 * {@link CircuitElement#load(CompoundTag)} are used.
 */
public final class SyncRegistry {

    private static final Map<Class<? extends CircuitElement>, SyncHandler<?>> REGISTRY = new HashMap<>();

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
     * Registers custom sync logic for a specific element class (exact {@link Class} match on {@link Object#getClass()}
     * during read/write).
     */
    public static <T extends CircuitElement> void register(
            Class<T> elementClass,
            BiConsumer<T, CompoundTag> customWriter,
            BiConsumer<T, CompoundTag> customReader) {
        register(elementClass, new SyncHandler<>(customWriter, customReader));
    }

    /**
     * Registers a composed {@link SyncHandler} for an element class.
     */
    public static <T extends CircuitElement> void register(Class<T> elementClass, SyncHandler<T> handler) {
        Objects.requireNonNull(elementClass, "elementClass");
        Objects.requireNonNull(handler, "handler");
        REGISTRY.put(elementClass, handler);
    }

    /**
     * Writes element data to {@code tag} for client sync (network-friendly subset or full save).
     */
    @SuppressWarnings("unchecked")
    public static void writeSyncData(CircuitElement element, CompoundTag tag) {
        ensureHandlersRegistered();
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(tag, "tag");
        SyncHandler<CircuitElement> handler =
                (SyncHandler<CircuitElement>) REGISTRY.get(element.getClass());
        if (handler != null) {
            handler.writer().accept(element, tag);
        } else {
            element.save(tag);
        }
    }

    /**
     * Reads synced data from {@code tag} into {@code element}.
     */
    @SuppressWarnings("unchecked")
    public static void readSyncData(CircuitElement element, CompoundTag tag) {
        ensureHandlersRegistered();
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(tag, "tag");
        SyncHandler<CircuitElement> handler =
                (SyncHandler<CircuitElement>) REGISTRY.get(element.getClass());
        if (handler != null) {
            handler.reader().accept(element, tag);
        } else {
            element.load(tag);
        }
    }
}
