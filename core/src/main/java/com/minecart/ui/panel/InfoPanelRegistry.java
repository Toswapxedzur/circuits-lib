package com.minecart.ui.panel;

import com.minecart.event.events.ElementInfoUpdateEvent;
import com.minecart.foundation.Level;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.registry.CircuitElementType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final Map<String, InfoPanelElementType<?>> TYPE_BINDINGS = new HashMap<>();
    private static final Map<InfoPanelElementType<?>, List<PanelTreeContribution<?>>> TREE_CONTRIBUTIONS =
            new LinkedHashMap<>();

    /**
     * Cross-cutting fragments grouped by element kind. Lists rather than singletons because more
     * than one fragment can layer onto the same kind (e.g. a future "tags" fragment alongside the
     * built-in position/rotation/lock). Order of registration is preserved → order of rows in the
     * built schema.
     */
    private static final List<InfoPanelFragment<CircuitNode>> NODE_FRAGMENTS = new ArrayList<>();
    private static final List<InfoPanelFragment<CircuitEdge>> EDGE_FRAGMENTS = new ArrayList<>();
    private static final List<InfoPanelFragment<CircuitComponent>> COMPONENT_FRAGMENTS = new ArrayList<>();

    private static final List<InfoPanelFragment.FragmentSaveHandler<CircuitNode>> NODE_FRAGMENT_SAVES = new ArrayList<>();
    private static final List<InfoPanelFragment.FragmentSaveHandler<CircuitEdge>> EDGE_FRAGMENT_SAVES = new ArrayList<>();
    private static final List<InfoPanelFragment.FragmentSaveHandler<CircuitComponent>> COMPONENT_FRAGMENT_SAVES = new ArrayList<>();

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

    public static <T extends CircuitElement> void bind(CircuitElementType<T> type,
                                                       InfoPanelElementType<? super T> panelType) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(panelType, "panelType");
        TYPE_BINDINGS.put(type.getTypeId(), panelType);
    }

    public static <T extends CircuitElement> void registerPanel(
            InfoPanelElementType<T> panelType, PanelTreeContribution<T> contribution) {
        Objects.requireNonNull(panelType, "panelType");
        Objects.requireNonNull(contribution, "contribution");
        TREE_CONTRIBUTIONS.computeIfAbsent(panelType, k -> new ArrayList<>()).add(contribution);
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
     * Adds a fragment that contributes rows to every {@link CircuitNode}'s panel. Multiple fragments
     * may be registered; their rows appear in registration order before the type-specific definition's
     * rows.
     */
    public static void registerNodeFragment(InfoPanelFragment<CircuitNode> fragment) {
        Objects.requireNonNull(fragment, "fragment");
        NODE_FRAGMENTS.add(fragment);
    }

    public static void registerEdgeFragment(InfoPanelFragment<CircuitEdge> fragment) {
        Objects.requireNonNull(fragment, "fragment");
        EDGE_FRAGMENTS.add(fragment);
    }

    public static void registerComponentFragment(InfoPanelFragment<CircuitComponent> fragment) {
        Objects.requireNonNull(fragment, "fragment");
        COMPONENT_FRAGMENTS.add(fragment);
    }

    public static void registerNodeFragmentSaveHandler(InfoPanelFragment.FragmentSaveHandler<CircuitNode> handler) {
        Objects.requireNonNull(handler, "handler");
        NODE_FRAGMENT_SAVES.add(handler);
    }

    public static void registerEdgeFragmentSaveHandler(InfoPanelFragment.FragmentSaveHandler<CircuitEdge> handler) {
        Objects.requireNonNull(handler, "handler");
        EDGE_FRAGMENT_SAVES.add(handler);
    }

    public static void registerComponentFragmentSaveHandler(InfoPanelFragment.FragmentSaveHandler<CircuitComponent> handler) {
        Objects.requireNonNull(handler, "handler");
        COMPONENT_FRAGMENT_SAVES.add(handler);
    }

    /**
     * @return the bare type-specific definition for {@code type}, or {@code null} if none was
     *         registered. Most callers want {@link #buildSchema(CircuitElement)} instead, which
     *         composes the cross-cutting fragments with the type-specific definition.
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
     * Builds the final, composed schema for {@code element}. Fragments matching the element's kind
     * (node / edge / component) contribute their rows first, then the type-specific definition
     * (if any) appends its rows. Returns {@code null} if neither any fragment nor any type-specific
     * definition would contribute — i.e. the element really has no panel — matching the legacy
     * "null = no panel" convention.
     *
     * <p>Fragment uniqueness check: the underlying {@link InfoPanelSchema.Builder} enforces unique
     * keys, so any fragment colliding with a type-specific key (or with another fragment) blows up
     * the schema build with an {@link IllegalArgumentException} — that's a programmer error worth
     * surfacing loudly rather than silently dropping a row.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static InfoPanelSchema buildSchema(CircuitElement element) {
        ComposeResult result = compose(element);
        return result != null ? result.schema() : null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ComposeResult compose(CircuitElement element) {
        if (element == null) return null;
        InfoPanelDefinition<?> typeDef = DEFINITIONS.get(element.getRegistryTypeId());

        // Title: prefer the type-specific definition's title if present, otherwise fall back to a
        // generic title based on kind. Built by asking the type-def to build its schema first;
        // we then re-build via the same Builder with fragments prepended.
        InfoPanelSchema typeSchema = null;
        if (typeDef != null) {
            try {
                typeSchema = ((InfoPanelDefinition) typeDef).build(element);
            } catch (RuntimeException ex) {
                // A buggy type definition shouldn't take down the whole panel — log and continue
                // with just the cross-cutting fragments.
                org.slf4j.LoggerFactory.getLogger(InfoPanelRegistry.class)
                        .warn("type-specific panel definition for {} threw on build",
                                element.getRegistryTypeId(), ex);
            }
        }

        String title;
        if (typeSchema != null) {
            title = typeSchema.getTitle();
        } else {
            title = defaultTitleFor(element);
        }
        PanelTreeBuilder treeBuilder = new PanelTreeBuilder(title);

        for (InfoPanelElementType<?> panelType : ancestryFor(element)) {
            List<PanelTreeContribution<?>> contributions = TREE_CONTRIBUTIONS.get(panelType);
            if (contributions == null) {
                continue;
            }
            for (PanelTreeContribution contribution : contributions) {
                contribution.contribute(element, treeBuilder);
            }
        }

        // Apply matching kind fragments first so position / rotation / lock rows appear at the
        // top — they're the "frame" the element-specific rows fill in.
        if (element instanceof CircuitNode node) {
            for (InfoPanelFragment<CircuitNode> f : NODE_FRAGMENTS) {
                f.contribute(node, new LegacyBuilderAdapter(treeBuilder));
            }
        } else if (element instanceof CircuitEdge edge) {
            for (InfoPanelFragment<CircuitEdge> f : EDGE_FRAGMENTS) {
                f.contribute(edge, new LegacyBuilderAdapter(treeBuilder));
            }
        } else if (element instanceof CircuitComponent comp) {
            for (InfoPanelFragment<CircuitComponent> f : COMPONENT_FRAGMENTS) {
                f.contribute(comp, new LegacyBuilderAdapter(treeBuilder));
            }
        }

        if (typeSchema != null) {
            for (PanelField f : typeSchema.getFields()) {
                treeBuilder.add(f);
            }
        }
        treeBuilder.action(PanelActionSpec.DELETE);

        InfoPanelSchema built = treeBuilder.buildSchema();
        if (built.getFields().isEmpty()) {
            // No fragment contributed and no type-specific definition — preserve the legacy "no
            // panel for this element" signal.
            return null;
        }
        return new ComposeResult(built, treeBuilder.saveBindings());
    }

    private static List<InfoPanelElementType<?>> ancestryFor(CircuitElement element) {
        InfoPanelElementType<?> type = TYPE_BINDINGS.get(element.getRegistryTypeId());
        if (type == null) {
            if (element instanceof CircuitNode) {
                type = InfoPanelTypes.NODE;
            } else if (element instanceof CircuitEdge) {
                type = InfoPanelTypes.EDGE;
            } else if (element instanceof CircuitComponent) {
                type = InfoPanelTypes.COMPONENT;
            } else {
                type = InfoPanelTypes.ELEMENT;
            }
        }
        ArrayList<InfoPanelElementType<?>> reversed = new ArrayList<>();
        for (InfoPanelElementType<?> p = type; p != null; p = p.parent()) {
            reversed.add(p);
        }
        ArrayList<InfoPanelElementType<?>> ordered = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) {
            ordered.add(reversed.get(i));
        }
        return ordered;
    }

    private static String defaultTitleFor(CircuitElement element) {
        // Fallback when the element has no type-specific definition. We use the registry id since
        // it's unambiguous and machine-readable; nicer human titles can be added later by either
        // registering a type-specific definition with an empty field list (just for the title) or
        // by extending CircuitElementType with a display-name accessor.
        String id = element.getRegistryTypeId();
        return id != null ? id : "Element";
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
        ComposeResult result = compose(el);
        if (result == null) return;
        InfoPanelSchema schema = result.schema();
        PanelSnapshot snapshot = evt.getSnapshot().filteredTo(schema.fieldKeys());
        PanelContext context = new PanelContext(el, snapshot, schema, evt);

        for (PanelTreeBuilder.SaveBinding binding : result.saveBindings()) {
            if (!binding.activeIn(schema)) {
                continue;
            }
            try {
                binding.save().apply(el, context);
            } catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(InfoPanelRegistry.class)
                        .warn("panel tree save handler threw on {}", el.getRegistryTypeId(), ex);
            }
        }

        // Fragment save handlers first: cross-cutting state (position / rotation / lock) should
        // be visible to any type-specific handler that runs after.
        if (el instanceof CircuitNode node) {
            for (InfoPanelFragment.FragmentSaveHandler<CircuitNode> h : NODE_FRAGMENT_SAVES) {
                try { h.apply(node, snapshot, evt); }
                catch (RuntimeException ex) {
                    org.slf4j.LoggerFactory.getLogger(InfoPanelRegistry.class)
                            .warn("node fragment save handler threw on {}", el.getRegistryTypeId(), ex);
                }
            }
        } else if (el instanceof CircuitEdge edge) {
            for (InfoPanelFragment.FragmentSaveHandler<CircuitEdge> h : EDGE_FRAGMENT_SAVES) {
                try { h.apply(edge, snapshot, evt); }
                catch (RuntimeException ex) {
                    org.slf4j.LoggerFactory.getLogger(InfoPanelRegistry.class)
                            .warn("edge fragment save handler threw on {}", el.getRegistryTypeId(), ex);
                }
            }
        } else if (el instanceof CircuitComponent comp) {
            for (InfoPanelFragment.FragmentSaveHandler<CircuitComponent> h : COMPONENT_FRAGMENT_SAVES) {
                try { h.apply(comp, snapshot, evt); }
                catch (RuntimeException ex) {
                    org.slf4j.LoggerFactory.getLogger(InfoPanelRegistry.class)
                            .warn("component fragment save handler threw on {}", el.getRegistryTypeId(), ex);
                }
            }
        }

        BiConsumer handler = SAVE_HANDLERS.get(el.getRegistryTypeId());
        if (handler == null) return;
        // Cast is checked by the registration contract: the handler keyed under typeId was
        // registered with the matching CircuitElementType<T>, so its parameter T is the same type
        // as the elements produced by that type's factory.
        handler.accept(el, snapshot);
        context.flushNotifications();
    }

    private record ComposeResult(InfoPanelSchema schema,
                                 List<PanelTreeBuilder.SaveBinding> saveBindings) {}

    private static final class LegacyBuilderAdapter extends InfoPanelSchema.Builder {
        private final PanelTreeBuilder<?> delegate;

        private LegacyBuilderAdapter(PanelTreeBuilder<?> delegate) {
            super("legacy");
            this.delegate = delegate;
        }

        @Override
        public InfoPanelSchema.Builder add(PanelField field) {
            delegate.add(field);
            return this;
        }
    }
}
