package com.minecart.server.handler;

import com.minecart.logic.ServerLevel;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.DragEndPayload;

import java.util.Objects;

/**
 * Server-side handler for {@link DragEndPayload}: releases every lease held by the gesture id on
 * the level's {@link com.minecart.logic.cascade.DragLeaseRegistry}. Idempotent — a duplicate or
 * stray end payload is silently ignored.
 */
public final class DragEndHandler implements PayloadHandler<DragEndPayload> {

    private final ServerLevel level;

    public DragEndHandler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void handle(DragEndPayload payload) {
        level.submit(() -> level.getDragLeases().release(payload.getGestureId()));
    }
}
