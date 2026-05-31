package com.minecart.display.render.registry;

import com.minecart.logic.CircuitElement;

/** One composable renderer step. */
public interface RenderPart<T extends CircuitElement> {

    default void layout(T element, RenderContext context) {
    }

    default void draw(T element, RenderContext context) {
    }
}
