package com.minecart.server.handler;

import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.CreateWorldPayload;
import com.minecart.protocol.payload.server.WorldLifecyclePayload;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Server-side handler for {@link CreateWorldPayload}: creates a new {@link ServerWorld} on the level (with a
 * fresh {@link java.util.UUID}), stamps the requested name, and broadcasts a CLIENT-bound
 * {@link WorldLifecyclePayload#insert(java.util.UUID, String)} so every connected client mirror picks it up.
 *
 * <p>Runs on the tick thread via {@link ServerLevel#submit(Runnable)} so world list mutations don't race the
 * simulation.
 */
public final class CreateWorldHandler implements PayloadHandler<CreateWorldPayload> {

    private final ServerLevel level;
    private final Consumer<Payload> broadcast;

    public CreateWorldHandler(ServerLevel level, Consumer<Payload> broadcast) {
        this.level = Objects.requireNonNull(level, "level");
        this.broadcast = Objects.requireNonNull(broadcast, "broadcast");
    }

    @Override
    public void handle(CreateWorldPayload payload) {
        level.submit(() -> apply(payload));
    }

    private void apply(CreateWorldPayload payload) {
        ServerWorld w = level.createWorld();
        String name = payload.getName();
        if (name != null && !name.isEmpty()) {
            w.setName(name);
        }
        broadcast.accept(WorldLifecyclePayload.insert(w.getId(), w.getName()));
    }
}
