package com.minecart.server.handler;

import com.minecart.foundation.World;
import com.minecart.logic.ServerLevel;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.RenameWorldPayload;
import com.minecart.protocol.payload.server.WorldLifecyclePayload;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Server-side handler for {@link RenameWorldPayload}: looks up the world and updates its display
 * {@link World#getName() name}, then broadcasts a CLIENT-bound
 * {@link WorldLifecyclePayload#rename(java.util.UUID, String)} so every connected client mirror picks up the
 * new label. Silent no-op if the id is unknown or the name is null/empty (clients can't show a blank label).
 */
public final class RenameWorldHandler implements PayloadHandler<RenameWorldPayload> {

    private final ServerLevel level;
    private final Consumer<Payload> broadcast;

    public RenameWorldHandler(ServerLevel level, Consumer<Payload> broadcast) {
        this.level = Objects.requireNonNull(level, "level");
        this.broadcast = Objects.requireNonNull(broadcast, "broadcast");
    }

    @Override
    public void handle(RenameWorldPayload payload) {
        // The dispatcher already marshals onto the tick thread; apply directly (no second submit hop).
        apply(payload);
    }

    private void apply(RenameWorldPayload payload) {
        World w = level.findWorld(payload.getWorldId());
        if (w == null) {
            return;
        }
        String name = payload.getName();
        if (name == null || name.isEmpty()) {
            return;
        }
        w.setName(name);
        broadcast.accept(WorldLifecyclePayload.rename(payload.getWorldId(), name));
    }
}
