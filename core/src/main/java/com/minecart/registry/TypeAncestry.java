package com.minecart.registry;

import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * The shared parent-chain composition kernel for the typed element registries — the root-first ancestry walk and
 * the Node/Edge/Component category fallback that {@code RenderRegistry} (display) and {@code InfoPanelRegistry}
 * (core) both need to resolve which contributions apply to an element. Both registries duplicated this identical
 * logic; it lives here (core) so each keeps only its own type constants + contribution gathering.
 */
public final class TypeAncestry {
    private TypeAncestry() {}

    /**
     * {@code leaf} and every ancestor reached via {@code parent}, ordered <b>root-first</b> — so a base type's
     * contributions are applied before (under) a leaf type's. Stops at the first {@code null} parent.
     */
    public static <T> List<T> rootFirst(T leaf, Function<T, T> parent) {
        ArrayList<T> chain = new ArrayList<>();
        for (T t = leaf; t != null; t = parent.apply(t)) {
            chain.add(t);
        }
        Collections.reverse(chain);
        return chain;
    }

    /**
     * The registry type to fall back on when an element has no explicit binding: its category default
     * ({@code node} / {@code edge} / {@code component}), or {@code element} for a bare {@link CircuitElement}.
     */
    public static <T> T byCategory(CircuitElement e, T node, T edge, T component, T element) {
        if (e instanceof CircuitNode) return node;
        if (e instanceof CircuitEdge) return edge;
        if (e instanceof CircuitComponent) return component;
        return element;
    }
}
