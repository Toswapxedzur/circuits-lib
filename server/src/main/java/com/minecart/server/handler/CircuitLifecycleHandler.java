package com.minecart.server.handler;

import com.minecart.foundation.Circuit;
import com.minecart.logic.ServerCircuit;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.server.CircuitLifecyclePayload;

import java.util.Objects;
import java.util.UUID;

/**
 * Server-side application of {@link CircuitLifecyclePayload}: adds or removes a {@link ServerCircuit} in a
 * {@link ServerWorld}. Registered on a server-only dispatcher; rejects payloads when the destination is not SERVER.
 */
public final class CircuitLifecycleHandler implements PayloadHandler<CircuitLifecyclePayload> {

    private final ServerLevel level;

    public CircuitLifecycleHandler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void handle(CircuitLifecyclePayload payload) {
        apply(payload, level);
    }

    public static void apply(CircuitLifecyclePayload payload, ServerLevel level) {
        UUID wid = payload.getWorldId();
        UUID cid = payload.getCircuitId();
        ServerWorld sw = (ServerWorld) level.findWorld(wid);
        if (sw == null) {
            throw new IllegalArgumentException("No world for id: " + wid);
        }
        if (payload.getKind() == CircuitLifecyclePayload.Kind.REMOVE) {
            Circuit c = sw.findCircuit(cid);
            if (c != null) {
                sw.removeCircuit(c);
            }
            return;
        }
        Circuit existing = sw.findCircuit(cid);
        if (existing != null) {
            sw.removeCircuit(existing);
        }
        ServerCircuit circuit = new ServerCircuit(cid);
        sw.addCircuit(circuit);
    }
}
