package com.minecart.server.handler;

import com.minecart.foundation.World;
import com.minecart.logic.ServerLevel;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.DeleteWorldPayload;
import com.minecart.protocol.payload.server.WorldLifecyclePayload;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Server-side handler for {@link DeleteWorldPayload}: looks up the requested world and destroys it on the
 * tick thread, then broadcasts a CLIENT-bound {@link WorldLifecyclePayload#remove(java.util.UUID)} so every
 * connected client mirror drops it. Silent no-op if the id is unknown.
 */
public final class DeleteWorldHandler implements PayloadHandler<DeleteWorldPayload> {

    private final ServerLevel level;
    private final Consumer<Payload> broadcast;

    public DeleteWorldHandler(ServerLevel level, Consumer<Payload> broadcast) {
        this.level = Objects.requireNonNull(level, "level");
        this.broadcast = Objects.requireNonNull(broadcast, "broadcast");
    }

    @Override
    public void handle(DeleteWorldPayload payload) {
        // The dispatcher already marshals onto the tick thread; apply directly (no second submit hop).
        apply(payload);
    }

    private void apply(DeleteWorldPayload payload) {
        World w = level.findWorld(payload.getWorldId());
        if (w == null) {
            return;
        }
        if (level.getWorlds().size() <= 1) {
            return;
        }
        level.destroyWorld(w);
        broadcast.accept(WorldLifecyclePayload.remove(payload.getWorldId()));
    }
}
