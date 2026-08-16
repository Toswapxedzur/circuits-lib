package com.minecart.server.handler;

import com.minecart.action.Actionable;
import com.minecart.logic.ServerLevel;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.ActionPayload;

import java.util.Objects;

/**
 * Resolves {@link ActionPayload} to an {@link Actionable} and runs it on the server tick thread.
 */
public final class ActionPayloadHandler implements PayloadHandler<ActionPayload> {

    private final ServerLevel level;

    public ActionPayloadHandler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void handle(ActionPayload payload) {
        // The dispatcher already marshals onto the tick thread; run the resolved action directly
        // rather than submitting a second time.
        receive(payload, level);
    }

    /**
     * Resolves the action for {@code payload} against {@code level} and runs it. Invoked on the tick
     * thread (the dispatcher has already marshalled execution there).
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
        runnable.run();
    }
}
