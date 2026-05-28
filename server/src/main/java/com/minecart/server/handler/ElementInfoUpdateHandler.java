package com.minecart.server.handler;

import com.minecart.event.events.ElementInfoUpdateEvent;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.ServerLevel;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.ElementInfoUpdatePayload;
import com.minecart.ui.panel.PanelSnapshot;

import java.util.Objects;
import java.util.UUID;

/**
 * Server-side handler for {@link ElementInfoUpdatePayload}. The payload is intentionally inert —
 * the handler just resolves the target element by {@code worldId + elementId} and posts an
 * {@link ElementInfoUpdateEvent} on the level event bus. Element-class listeners (registered in
 * static initialisers, mirroring how {@code Resistor} registers its action handlers) decide which
 * snapshot fields they understand and whether to apply them.
 *
 * <p>The whole flow runs on the level's tick thread via {@link ServerLevel#submit}, same as the
 * other write-path handlers, so listeners can mutate element state without worrying about races
 * with concurrent ticks.
 */
public final class ElementInfoUpdateHandler implements PayloadHandler<ElementInfoUpdatePayload> {

    private final ServerLevel level;

    public ElementInfoUpdateHandler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void handle(ElementInfoUpdatePayload payload) {
        level.submit(() -> apply(payload));
    }

    private void apply(ElementInfoUpdatePayload payload) {
        World world = level.findWorld(payload.getWorldId());
        if (world == null) {
            return;
        }
        CircuitElement el = findElement(world, payload.getElementId());
        if (el == null) {
            return;
        }
        PanelSnapshot snapshot = payload.getSnapshot();
        if (snapshot == null) {
            // Treat a missing snapshot as an empty one rather than throwing — the event listeners
            // will simply find no relevant fields and do nothing, which is the right behaviour.
            snapshot = PanelSnapshot.builder().build();
        }
        // Post on the level bus rather than a per-element bus so a listener that handles many
        // element types (e.g. a debug logger) can subscribe once. Listeners filter by
        // `evt.getElement() instanceof MyElementType` to scope down.
        level.post(new ElementInfoUpdateEvent(el, snapshot));
    }

    private static CircuitElement findElement(World world, UUID id) {
        if (id == null) {
            return null;
        }
        for (Circuit c : world.getCircuits()) {
            CircuitElement el = c.findElement(id);
            if (el != null) {
                return el;
            }
        }
        return null;
    }
}
