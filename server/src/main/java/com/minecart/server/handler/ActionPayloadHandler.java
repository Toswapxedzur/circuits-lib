package com.minecart.server.handler;

import com.minecart.action.Actionable;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.Level;
import com.minecart.logic.ServerLevel;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.ActionPayload;

import java.util.Objects;

/**
 * Resolves {@link ActionPayload} to an {@link Actionable} and {@linkplain ServerLevel#submit(Runnable) submits} it.
 */
public final class ActionPayloadHandler implements PayloadHandler<ActionPayload> {

    private final ServerLevel level;

    public ActionPayloadHandler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    public ServerLevel getLevel() {
        return level;
    }

    @Override
    public void handle(ActionPayload payload) {
        receive(payload, level);
    }

    /**
     * Queues the action for execution when the server level drains its action queue.
     */
    public static void receive(ActionPayload payload, ServerLevel level) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(level, "level");
        Runnable runnable = Actionable.fromPayload(
                level,
                payload.getWorldId(),
                payload.getCircuitId(),
                payload.getElementId(),
                payload.getAction());
        level.submit(runnable);
    }

    /**
     * Resolves when the target {@link Circuit} is already known.
     */
    public static Runnable toRunnable(ActionPayload payload, Circuit circuit) {
        if (!payload.getCircuitId().equals(circuit.getId())) {
            throw new IllegalArgumentException(
                    "Circuit id mismatch: payload " + payload.getCircuitId() + ", actual " + circuit.getId());
        }
        return Actionable.fromPayload(circuit, payload.getElementId(), payload.getAction());
    }

    /**
     * Resolves using a generic {@link Level} (same rules as {@link Actionable#fromPayload(Level, java.util.UUID, java.util.UUID, java.util.UUID, com.minecart.action.Action)}).
     */
    public static Runnable toRunnable(ActionPayload payload, Level level) {
        return Actionable.fromPayload(
                level,
                payload.getWorldId(),
                payload.getCircuitId(),
                payload.getElementId(),
                payload.getAction());
    }
}
