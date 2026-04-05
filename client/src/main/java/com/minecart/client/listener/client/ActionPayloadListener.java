package com.minecart.client.listener.client;

import com.minecart.action.Action;
import com.minecart.client.handler.client.ActionPayloadHandler;
import com.minecart.client.payload.client.ActionPayload;
import com.minecart.foundation.Circuit;
import com.minecart.logic.CircuitElement;
import com.minecart.foundation.World;

import java.util.UUID;

/**
 * Factory methods for {@link ActionPayload} when the client records a player action. Server execution is
 * {@link ActionPayloadHandler}.
 */
public final class ActionPayloadListener {

    private ActionPayloadListener() {
    }

    public static ActionPayload fromElementAction(CircuitElement element, Action action) {
        World w = element.getWorld();
        Circuit c = element.getCircuit();
        if (w == null || c == null) {
            throw new IllegalArgumentException("Element must have world and circuit set");
        }
        return new ActionPayload(w.getId(), c.getId(), element.getId(), action);
    }

    public static ActionPayload fromIds(UUID worldId, UUID circuitId, UUID elementId, Action action) {
        return new ActionPayload(worldId, circuitId, elementId, action);
    }

    public static ActionPayload fromCircuitAction(Circuit circuit, UUID elementId, Action action) {
        return new ActionPayload(null, circuit.getId(), elementId, action);
    }

    public static ActionPayload fromWorldCircuitAction(World world, Circuit circuit, UUID elementId, Action action) {
        return new ActionPayload(world.getId(), circuit.getId(), elementId, action);
    }
}
