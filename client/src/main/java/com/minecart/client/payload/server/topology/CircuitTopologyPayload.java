package com.minecart.client.payload.server.topology;

import com.minecart.client.payload.Payload;
import com.minecart.client.payload.PayloadRegistry;
import com.minecart.client.payload.PayloadType;
import com.minecart.logic.Circuit;
import com.minecart.logic.ServerCircuit;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.logic.World;
import com.minecart.serialization.tag.CompoundTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Network payload for circuit topology: an ordered list of {@link CircuitTopologyChange} steps.
 * Application and tag I/O live in {@link CircuitTopologyReceiver}.
 */
public class CircuitTopologyPayload extends Payload {

    public static final String PAYLOAD_ID = "minecart.circuit_topology_payload";

    public static final PayloadType<CircuitTopologyPayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, CircuitTopologyPayload::new);

    protected UUID worldId;
    protected UUID circuitId;
    protected final List<CircuitTopologyChange> changes = new ArrayList<>();

    public CircuitTopologyPayload() {
    }

    public CircuitTopologyPayload(UUID worldId, UUID circuitId, List<CircuitTopologyChange> changes) {
        this.worldId = worldId;
        this.circuitId = circuitId;
        if (changes != null) {
            this.changes.addAll(changes);
        }
    }

    @Override
    public String getPayloadId() {
        return PAYLOAD_ID;
    }

    public UUID getWorldId() {
        return worldId;
    }

    public void setWorldId(UUID worldId) {
        this.worldId = worldId;
    }

    public UUID getCircuitId() {
        return circuitId;
    }

    public void setCircuitId(UUID circuitId) {
        this.circuitId = circuitId;
    }

    public List<CircuitTopologyChange> getChanges() {
        return Collections.unmodifiableList(changes);
    }

    /**
     * Resolves the circuit on {@code level} and applies ordered topology steps.
     */
    public void applyTo(ServerLevel level) {
        CircuitTopologyReceiver.ResolvedWorldCircuit resolved =
                CircuitTopologyReceiver.resolveWorldAndCircuit(level, worldId, circuitId);
        Circuit circuit = resolved.circuit();
        if (!(circuit instanceof ServerCircuit sc)) {
            throw new IllegalStateException("Circuit is not a ServerCircuit");
        }
        World w = resolved.world();
        if (!(w instanceof ServerWorld sw)) {
            throw new IllegalStateException("World is not a ServerWorld");
        }
        if (circuitId != null && !circuitId.equals(circuit.getId())) {
            throw new IllegalArgumentException("Circuit id mismatch: payload " + circuitId + ", actual " + circuit.getId());
        }
        CircuitTopologyReceiver.applyDelta(sw, sc, changes);
    }

    /**
     * Applies ordered steps to an already-resolved server circuit.
     */
    public void applyTo(ServerWorld world, ServerCircuit circuit) {
        if (circuitId != null && !circuitId.equals(circuit.getId())) {
            throw new IllegalArgumentException("Circuit id mismatch: payload " + circuitId + ", actual " + circuit.getId());
        }
        if (circuit.getWorld() != world) {
            throw new IllegalArgumentException("Circuit does not belong to the given world");
        }
        CircuitTopologyReceiver.applyDelta(world, circuit, changes);
    }

    @Override
    protected void savePayload(CompoundTag tag) {
        CircuitTopologyReceiver.writePayload(this, tag);
    }

    @Override
    protected void loadPayload(CompoundTag tag) {
        CircuitTopologyReceiver.readPayload(this, tag);
    }
}
