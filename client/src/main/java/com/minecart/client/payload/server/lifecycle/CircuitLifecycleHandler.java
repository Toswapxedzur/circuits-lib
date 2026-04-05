package com.minecart.client.payload.server.lifecycle;

import com.minecart.client.logic.ClientCircuit;
import com.minecart.client.logic.ClientLevel;
import com.minecart.client.logic.ClientWorld;
import com.minecart.client.payload.PayloadHandler;
import com.minecart.foundation.Circuit;
import com.minecart.logic.ServerCircuit;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;

import java.util.Objects;
import java.util.UUID;

/**
 * Applies {@link CircuitLifecyclePayload}: wire destination is {@link com.minecart.client.payload.Payload.Destination#CLIENT};
 * use {@link #forClient(ClientLevel)} for incoming replication, or {@link #forServer(ServerLevel)} for local authority only.
 */
public final class CircuitLifecycleHandler implements PayloadHandler<CircuitLifecyclePayload> {

    private final ServerLevel serverLevel;
    private final ClientLevel clientLevel;

    public static CircuitLifecycleHandler forServer(ServerLevel level) {
        return new CircuitLifecycleHandler(Objects.requireNonNull(level, "level"), null);
    }

    public static CircuitLifecycleHandler forClient(ClientLevel level) {
        return new CircuitLifecycleHandler(null, Objects.requireNonNull(level, "level"));
    }

    private CircuitLifecycleHandler(ServerLevel serverLevel, ClientLevel clientLevel) {
        this.serverLevel = serverLevel;
        this.clientLevel = clientLevel;
    }

    @Override
    public void handle(CircuitLifecyclePayload payload) {
        if (serverLevel != null) {
            applyServer(payload, serverLevel);
        } else {
            applyClient(payload, clientLevel);
        }
    }

    private static void applyServer(CircuitLifecyclePayload payload, ServerLevel level) {
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

    private static void applyClient(CircuitLifecyclePayload payload, ClientLevel level) {
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
