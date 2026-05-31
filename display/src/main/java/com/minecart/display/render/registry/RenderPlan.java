package com.minecart.display.render.registry;

import com.minecart.logic.CircuitElement;

import java.util.List;

/** Immutable ordered render part list for one element. */
public final class RenderPlan<T extends CircuitElement> {

    private final List<RenderPart<? super T>> parts;

    RenderPlan(List<RenderPart<? super T>> parts) {
        this.parts = parts;
    }

    public void layout(T element, RenderContext context) {
        for (RenderPart<? super T> part : parts) {
            part.layout(element, context);
        }
    }

    public void draw(T element, RenderContext context) {
        for (RenderPart<? super T> part : parts) {
            part.draw(element, context);
        }
    }
}
