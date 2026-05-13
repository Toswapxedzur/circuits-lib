package com.minecart.client.handler;

import com.minecart.client.logic.ClientLevel;
import com.minecart.foundation.World;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.server.WorldLifecyclePayload;

import java.util.Objects;
import java.util.UUID;

/**
 * Client-side application of {@link WorldLifecyclePayload}: creates or destroys a {@link com.minecart.client.logic.ClientWorld}
 * to mirror the server's world set.
 */
public final class WorldLifecycleHandler implements PayloadHandler<WorldLifecyclePayload> {

    private final ClientLevel level;

    public WorldLifecycleHandler(ClientLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void handle(WorldLifecyclePayload payload) {
        apply(payload, level);
    }

    public static void apply(WorldLifecyclePayload payload, ClientLevel level) {
        UUID wid = payload.getWorldId();
        switch (payload.getKind()) {
            case REMOVE -> {
                World w = level.findWorld(wid);
                if (w != null) {
                    level.destroyWorld(w);
                }
            }
            case INSERT -> {
                level.getOrCreateWorld(wid);
                level.renameWorld(wid, payload.getName());
            }
            case RENAME -> level.renameWorld(wid, payload.getName());
        }
    }
}
