package com.minecart.display.render.registry;

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

/** Display-side typed renderer registry with parent-chain composition. */
public final class RenderRegistry {

    private static final Map<String, RenderElementType<?>> TYPE_BINDINGS = new HashMap<>();
    private static final Map<RenderElementType<?>, List<RenderContribution<?>>> CONTRIBUTIONS =
            new LinkedHashMap<>();
    /**
     * Memoized render plans keyed by the resolved leaf {@link RenderElementType}. Registrations are
     * static after {@link DefaultRenderRegistrations#install()} runs, so the plan (and its part
     * instances) is identical every frame — rebuilding it per layout/draw call allocated a fresh
     * builder + N part objects ~2*N times per frame. The cache is invalidated whenever
     * {@link #register} or {@link #bind} mutates the registry, so a late/dynamic registration still
     * produces a correct plan on the next call.
     */
    private static final Map<RenderElementType<?>, RenderPlan<?>> PLAN_CACHE = new HashMap<>();

    private RenderRegistry() {}

    public static <T extends CircuitElement> void bind(CircuitElementType<T> elementType,
                                                       RenderElementType<? super T> renderType) {
        Objects.requireNonNull(elementType, "elementType");
        Objects.requireNonNull(renderType, "renderType");
        TYPE_BINDINGS.put(elementType.getTypeId(), renderType);
        PLAN_CACHE.clear();
    }

    public static <T extends CircuitElement> void register(
            RenderElementType<T> renderType, RenderContribution<T> contribution) {
        Objects.requireNonNull(renderType, "renderType");
        Objects.requireNonNull(contribution, "contribution");
        CONTRIBUTIONS.computeIfAbsent(renderType, k -> new ArrayList<>()).add(contribution);
        PLAN_CACHE.clear();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void layout(CircuitElement element, RenderContext context) {
        RenderPlan plan = planFor(element);
        plan.layout(element, context);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void draw(CircuitElement element, RenderContext context) {
        RenderPlan plan = planFor(element);
        plan.draw(element, context);
    }

    private static RenderPlan<?> planFor(CircuitElement element) {
        RenderElementType<?> leaf = leafTypeFor(element);
        RenderPlan<?> cached = PLAN_CACHE.get(leaf);
        if (cached == null) {
            cached = buildPlan(leaf);
            PLAN_CACHE.put(leaf, cached);
        }
        return cached;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RenderPlan<?> buildPlan(RenderElementType<?> leaf) {
        RenderTreeBuilder builder = new RenderTreeBuilder();
        for (RenderElementType<?> type : ancestryOf(leaf)) {
            List<RenderContribution<?>> contributions = CONTRIBUTIONS.get(type);
            if (contributions == null) {
                continue;
            }
            for (RenderContribution contribution : contributions) {
                contribution.contribute(builder);
            }
        }
        return builder.build();
    }

    private static RenderElementType<?> leafTypeFor(CircuitElement element) {
        RenderElementType<?> type = TYPE_BINDINGS.get(element.getRegistryTypeId());
        if (type == null) {
            if (element instanceof CircuitNode) {
                type = RenderTypes.NODE;
            } else if (element instanceof CircuitEdge) {
                type = RenderTypes.EDGE;
            } else if (element instanceof CircuitComponent) {
                type = RenderTypes.COMPONENT;
            } else {
                type = RenderTypes.ELEMENT;
            }
        }
        return type;
    }

    private static List<RenderElementType<?>> ancestryOf(RenderElementType<?> leaf) {
        ArrayList<RenderElementType<?>> reversed = new ArrayList<>();
        for (RenderElementType<?> p = leaf; p != null; p = p.parent()) {
            reversed.add(p);
        }
        ArrayList<RenderElementType<?>> ordered = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) {
            ordered.add(reversed.get(i));
        }
        return ordered;
    }
}
