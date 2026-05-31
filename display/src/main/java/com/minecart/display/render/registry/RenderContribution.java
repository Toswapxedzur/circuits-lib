package com.minecart.display.render.registry;

import com.minecart.logic.CircuitElement;

@FunctionalInterface
public interface RenderContribution<T extends CircuitElement> {
    void contribute(RenderTreeBuilder<T> builder);
}
