package com.minecart.ui.panel;

import com.minecart.event.events.ElementInfoUpdateEvent;
import com.minecart.foundation.Level;
import com.minecart.logic.CircuitElement;
import com.minecart.registry.CircuitElementType;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Maps a {@link CircuitElementType} to its info-panel definition and (optional) save handler.
 * Mirrors the shape of {@link com.minecart.registry.CircuitElementRegistry} /
 * {@link com.minecart.registry.ElementInfoRegistry}: keyed by the registry id of the element type,
 * looked up at panel-open time on the client and at event-dispatch time on the server.
 *
 * <p>There are two complementary halves:
 * <ul>
 *   <li><b>{@link InfoPanelDefinition}</b> — the schema-builder, used client-side by the renderer.</li>
 *   <li><b>Save handler</b> ({@code BiConsumer<T, PanelSnapshot>}) — invoked server-side when a
 *       client's panel-save payload lands, after {@link ElementInfoUpdateEvent} fires.</li>
 * </ul>
 * Both are optional and independent: an element can declare a read-only panel by registering only
 * a definition, or accept server-side updates via the raw event API without registering a save
 * handler at all. For the common case where one element class wants both, registering them
 * together via the static dispatch is the cleanest path:
 *
 * <pre>{@code
 * static {
 *     InfoPanelRegistry.register(AllComponents.RESISTOR, r ->
 *         InfoPanelSchema.builder("Resistor")
 *             .add(new NumberFieldSpec("resistance", "Resistance (Ω)", r.getResistance()))
 *             .build()
 *     );
 *     InfoPanelRegistry.registerSaveHandler(AllComponents.RESISTOR, (r, snap) ->
 *         snap.getDouble("resistance").filter(v -> v > 0).ifPresent(r::setResistance)
 *     );
 * }
 * }</pre>
 *
 * <p>For the save handlers to actually fire, each {@link Level} that processes panel-save events
 * must have a dispatcher attached via {@link #installLevelListener(Level)}. Servers call this once
 * during level setup; clients don't need it (panel-save events are never posted client-side — the
 * client just sends the payload).
 */
public final class InfoPanelRegistry {

    private static final Map<String, InfoPanelDefinition<?>> DEFINITIONS = new HashMap<>();
    private static final Map<String, BiConsumer<? extends CircuitElement, PanelSnapshot>> SAVE_HANDLERS = new HashMap<>();

    private InfoPanelRegistry() {}

    public static <T extends CircuitElement> void register(CircuitElementType<T> type,
                                                           InfoPanelDefinition<T> definition) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(definition, "definition");
        if (DEFINITIONS.containsKey(type.getTypeId())) {
            throw new IllegalArgumentException(
                    "Info panel already registered for element type '" + type.getTypeId() + "'");
        }
        DEFINITIONS.put(type.getTypeId(), definition);
    }

    public static <T extends CircuitElement> void registerSaveHandler(CircuitElementType<T> type,
                                                                      BiConsumer<T, PanelSnapshot> handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        if (SAVE_HANDLERS.containsKey(type.getTypeId())) {
            throw new IllegalArgumentException(
                    "Save handler already registered for element type '" + type.getTypeId() + "'");
        }
        SAVE_HANDLERS.put(type.getTypeId(), handler);
    }

    /**
     * @return the definition for {@code type}, or {@code null} if none was registered. Callers
     *         should treat {@code null} as "this element has no info panel", not an error.
     */
    @SuppressWarnings("unchecked")
    public static <T extends CircuitElement> InfoPanelDefinition<T> get(CircuitElementType<T> type) {
        if (type == null) return null;
        return (InfoPanelDefinition<T>) DEFINITIONS.get(type.getTypeId());
    }

    /**
     * Convenience lookup by raw registry id, used by the renderer where we have an element instance
     * and read {@code element.getRegistryTypeId()} without round-tripping through the type registry.
     */
    public static InfoPanelDefinition<?> getById(String typeId) {
        if (typeId == null) return null;
        return DEFINITIONS.get(typeId);
    }

    /**
     * Attaches a {@link ElementInfoUpdateEvent} listener to {@code level} that routes the event to
     * the appropriate static save handler based on the element's registry id. Idempotent only by
     * not being called twice — duplicate calls would register two listeners, which is benign but
     * wasteful. The server's level boot path calls this once.
     */
    public static void installLevelListener(Level level) {
        Objects.requireNonNull(level, "level");
        level.register(ElementInfoUpdateEvent.class, InfoPanelRegistry::dispatch);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void dispatch(ElementInfoUpdateEvent evt) {
        CircuitElement el = evt.getElement();
        if (el == null) return;
        BiConsumer handler = SAVE_HANDLERS.get(el.getRegistryTypeId());
        if (handler == null) return;
        // Cast is checked by the registration contract: the handler keyed under typeId was
        // registered with the matching CircuitElementType<T>, so its parameter T is the same type
        // as the elements produced by that type's factory.
        handler.accept(el, evt.getSnapshot());
    }
}
