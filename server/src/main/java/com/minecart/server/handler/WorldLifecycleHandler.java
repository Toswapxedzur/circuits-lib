package com.minecart.server.handler;

import com.minecart.foundation.World;
import com.minecart.logic.ServerLevel;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.server.WorldLifecyclePayload;

import java.util.Objects;
import java.util.UUID;

/**
 * Server-side application of {@link WorldLifecyclePayload}: creates or destroys a {@link com.minecart.logic.ServerWorld}.
 */
public final class WorldLifecycleHandler implements PayloadHandler<WorldLifecyclePayload> {

    private final ServerLevel level;

    public WorldLifecycleHandler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void handle(WorldLifecyclePayload payload) {
        apply(payload, level);
    }

    public static void apply(WorldLifecyclePayload payload, ServerLevel level) {
        UUID wid = payload.getWorldId();
        if (payload.getKind() == WorldLifecyclePayload.Kind.REMOVE) {
            World w = level.findWorld(wid);
            if (w != null) {
                level.destroyWorld(w);
            }
            return;
        }
        level.getOrCreateWorld(wid);
    }
}
