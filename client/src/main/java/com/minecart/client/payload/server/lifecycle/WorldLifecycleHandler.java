package com.minecart.client.payload.server.lifecycle;

import com.minecart.client.logic.ClientLevel;
import com.minecart.client.payload.PayloadHandler;
import com.minecart.logic.Level;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.World;

import java.util.Objects;
import java.util.UUID;

/**
 * Applies {@link WorldLifecyclePayload}: wire destination is {@link com.minecart.client.payload.Payload.Destination#CLIENT};
 * use {@link #forClient(ClientLevel)} for incoming replication, or {@link #forServer(ServerLevel)} for local authority only.
 */
public final class WorldLifecycleHandler implements PayloadHandler<WorldLifecyclePayload> {

    private final ServerLevel serverLevel;
    private final ClientLevel clientLevel;

    public static WorldLifecycleHandler forServer(ServerLevel level) {
        return new WorldLifecycleHandler(Objects.requireNonNull(level, "level"), null);
    }

    public static WorldLifecycleHandler forClient(ClientLevel level) {
        return new WorldLifecycleHandler(null, Objects.requireNonNull(level, "level"));
    }

    private WorldLifecycleHandler(ServerLevel serverLevel, ClientLevel clientLevel) {
        this.serverLevel = serverLevel;
        this.clientLevel = clientLevel;
    }

    @Override
    public void handle(WorldLifecyclePayload payload) {
        if (serverLevel != null) {
            apply(payload, serverLevel);
        } else {
            apply(payload, clientLevel);
        }
    }

    private static void apply(WorldLifecyclePayload payload, Level level) {
        UUID wid = payload.getWorldId();
        if (wid == null) {
            throw new IllegalArgumentException("Missing world id");
        }
        if (payload.getKind() == WorldLifecyclePayload.Kind.REMOVE) {
            World w = level.findWorld(wid);
            if (w != null) {
                if (level instanceof ServerLevel sl) {
                    sl.destroyWorld(w);
                } else if (level instanceof ClientLevel cl) {
                    cl.destroyWorld(w);
                }
            }
            return;
        }
        if (level instanceof ServerLevel sl) {
            sl.getOrCreateWorld(wid);
        } else if (level instanceof ClientLevel cl) {
            cl.getOrCreateWorld(wid);
        }
    }
}
