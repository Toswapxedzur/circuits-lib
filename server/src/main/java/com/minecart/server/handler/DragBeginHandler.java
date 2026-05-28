package com.minecart.server.handler;

import com.minecart.logic.ServerLevel;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.DragBeginPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Server-side handler for {@link DragBeginPayload}: forwards the request to the level's
 * {@link com.minecart.logic.cascade.DragLeaseRegistry}. Failure (lease conflict with another
 * gesture) is currently logged but not communicated back to the client — Phase 2c+ will add a
 * per-channel {@code DragReplyPayload} response for that.
 */
public final class DragBeginHandler implements PayloadHandler<DragBeginPayload> {

    private static final Logger log = LoggerFactory.getLogger(DragBeginHandler.class);

    private final ServerLevel level;

    public DragBeginHandler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void handle(DragBeginPayload payload) {
        level.submit(() -> apply(payload));
    }

    private void apply(DragBeginPayload payload) {
        boolean acquired = level.getDragLeases().tryAcquire(
                payload.getGestureId(), payload.getElementIds());
        if (!acquired) {
            log.debug("drag-begin: refused gesture {} on {} elements — conflict with active lease",
                    payload.getGestureId(), payload.getElementIds().size());
        }
    }
}
