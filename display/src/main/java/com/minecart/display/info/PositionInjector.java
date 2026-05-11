package com.minecart.display.info;

import com.minecart.event.events.ElementInfoInjectEvent;
import com.minecart.foundation.Level;
import com.minecart.logic.CircuitNode;

/**
 * Subscribes to {@link ElementInfoInjectEvent} on a {@link Level} and attaches a {@link PositionInfo} to
 * every {@link CircuitNode}. No-op if a {@link PositionInfo} was already injected by an earlier listener
 * (e.g. a saved layout being restored later in the load path).
 *
 * <p>The injected position defaults to {@code (0,0)}. Real layout (auto force-directed, hand-placed via
 * editor, etc.) is the renderer's responsibility — it just mutates the existing {@link PositionInfo}.
 */
public final class PositionInjector {

    private PositionInjector() {}

    /**
     * Registers the listener on {@code level}. Call once during display startup, after any
     * {@link com.minecart.foundation.World} you want positioned has been attached but before circuits
     * are loaded or built (so the listener is in place when {@link ElementInfoInjectEvent} fires).
     */
    public static void attach(Level level) {
        level.register(ElementInfoInjectEvent.class, event -> {
            if (!(event.getElement() instanceof CircuitNode)) {
                return;
            }
            if (event.isPresent(DisplayElementInfos.POSITION)) {
                return;
            }
            event.inject(DisplayElementInfos.POSITION, new PositionInfo());
        });
    }
}
