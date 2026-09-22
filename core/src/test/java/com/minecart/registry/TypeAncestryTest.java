package com.minecart.registry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the shared composition kernel: {@link TypeAncestry#rootFirst} walks a parent chain and returns it
 * ROOT-first (base before leaf), and {@link TypeAncestry#byCategory} selects by Node/Edge/Component category.
 * These replaced identical private logic in RenderRegistry (display) and InfoPanelRegistry (core).
 */
class TypeAncestryTest {

    /** A minimal parent-linked node to exercise the generic walk without any registry type. */
    private record Chain(String name, Chain parent) {}

    @Test
    void rootFirstOrdersBaseBeforeLeaf() {
        Chain root = new Chain("root", null);
        Chain mid = new Chain("mid", root);
        Chain leaf = new Chain("leaf", mid);

        List<Chain> ordered = TypeAncestry.rootFirst(leaf, Chain::parent);

        assertEquals(List.of("root", "mid", "leaf"),
                ordered.stream().map(Chain::name).toList(),
                "ancestry must be root-first so a base type's contributions apply before the leaf's");
    }

    @Test
    void rootFirstOfALoneLeafIsJustThatLeaf() {
        Chain solo = new Chain("solo", null);
        assertEquals(List.of(solo), TypeAncestry.rootFirst(solo, Chain::parent));
    }

    // byCategory's Node/Edge/Component selection is exercised end-to-end by PanelFragmentTest against real
    // CircuitElements (a bare CircuitElement that is none of the three can't be constructed here).
}
