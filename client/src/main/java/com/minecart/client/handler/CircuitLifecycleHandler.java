package com.minecart.client.handler;

import com.minecart.client.logic.ClientCircuit;
import com.minecart.client.logic.ClientLevel;
import com.minecart.client.logic.ClientWorld;
import com.minecart.foundation.Circuit;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.server.CircuitLifecyclePayload;

import java.util.Objects;
import java.util.UUID;

/**
 * Client-side application of {@link CircuitLifecyclePayload}: adds or removes a {@link ClientCircuit} in a
 * {@link ClientWorld} to mirror the server's circuit topology.
 */
public final class CircuitLifecycleHandler implements PayloadHandler<CircuitLifecyclePayload> {

    private final ClientLevel level;

    public CircuitLifecycleHandler(ClientLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void handle(CircuitLifecyclePayload payload) {
        apply(payload, level);
    }

    public static void apply(CircuitLifecyclePayload payload, ClientLevel level) {
        UUID wid = payload.getWorldId();
        UUID cid = payload.getCircuitId();
        ClientWorld cw = level.getOrCreateWorld(wid);
        if (payload.getKind() == CircuitLifecyclePayload.Kind.REMOVE) {
            Circuit c = cw.findCircuit(cid);
            if (c != null) {
                cw.removeCircuit(c);
            }
            return;
        }
        Circuit existing = cw.findCircuit(cid);
        if (existing != null) {
            cw.removeCircuit(existing);
        }
        ClientCircuit circuit = new ClientCircuit(cid);
        cw.addCircuit(circuit);
    }
}
