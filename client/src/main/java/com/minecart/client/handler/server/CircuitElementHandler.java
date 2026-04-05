package com.minecart.client.handler.server;

import com.minecart.client.logic.ClientCircuit;
import com.minecart.client.logic.ClientLevel;
import com.minecart.client.logic.ClientWorld;
import com.minecart.client.network.SyncRegistry;
import com.minecart.client.payload.PayloadHandler;
import com.minecart.client.payload.server.CircuitElementChange;
import com.minecart.client.payload.server.CircuitElementPayload;
import com.minecart.foundation.Circuit;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.foundation.Level;
import com.minecart.foundation.World;
import com.minecart.misc.CoreStrings;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Applies {@link CircuitElementPayload} on a {@link ClientLevel} (server → client replication).
 */
public final class CircuitElementHandler implements PayloadHandler<CircuitElementPayload> {

    private final ClientLevel level;

    public CircuitElementHandler(ClientLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    public ClientLevel getLevel() {
        return level;
    }

    @Override
    public void handle(CircuitElementPayload payload) {
        ResolvedWorldCircuit resolved = resolveWorldAndCircuit(level, payload.getWorldId(), payload.getCircuitId());
        applyDelta(resolved.world(), resolved.circuit(), payload.getChanges());
    }

    /**
     * Applies ordered steps when world and circuit are already resolved.
     */
    public static void handle(CircuitElementPayload payload, ClientWorld world, ClientCircuit circuit) {
        if (!payload.getCircuitId().equals(circuit.getId())) {
            throw new IllegalArgumentException(
                    "Circuit id mismatch: payload " + payload.getCircuitId() + ", actual " + circuit.getId());
        }
        if (circuit.getWorld() != world) {
            throw new IllegalArgumentException("Circuit does not belong to the given world");
        }
        applyDelta(world, circuit, payload.getChanges());
    }

    private static ResolvedWorldCircuit resolveWorldAndCircuit(ClientLevel level, UUID worldId, UUID circuitId) {
        Objects.requireNonNull(circuitId, "Missing circuit id");
        if (worldId != null) {
            World w = level.findWorld(worldId);
            if (w == null) {
                throw new IllegalArgumentException("No world for id: " + worldId);
            }
            if (!(w instanceof ClientWorld cw)) {
                throw new IllegalStateException("World is not a ClientWorld");
            }
            Circuit c = cw.findCircuit(circuitId);
            if (c == null) {
                throw new IllegalArgumentException("No circuit for id: " + circuitId + " in world " + worldId);
            }
            if (!(c instanceof ClientCircuit cc)) {
                throw new IllegalStateException("Circuit is not a ClientCircuit");
            }
            return new ResolvedWorldCircuit(cw, cc);
        }
        Circuit c = level.findCircuit(circuitId);
        if (c == null) {
            throw new IllegalArgumentException("No circuit for id: " + circuitId + " in level");
        }
        if (!(c instanceof ClientCircuit cc)) {
            throw new IllegalStateException("Circuit is not a ClientCircuit");
        }
        World w = findWorldContaining(c, level);
        if (w == null) {
            throw new IllegalArgumentException("Circuit " + circuitId + " is not attached to any world on this level");
        }
        if (!(w instanceof ClientWorld cw)) {
            throw new IllegalStateException("World is not a ClientWorld");
        }
        return new ResolvedWorldCircuit(cw, cc);
    }

    private static World findWorldContaining(Circuit circuit, Level level) {
        for (World w : level.getWorlds()) {
            if (w.getCircuits().contains(circuit)) {
                return w;
            }
        }
        return null;
    }

    private record ResolvedWorldCircuit(ClientWorld world, ClientCircuit circuit) {
    }

    private static void applyDelta(ClientWorld world, ClientCircuit circuit, List<CircuitElementChange> ops) {
        if (circuit.getWorld() != world) {
            throw new IllegalArgumentException("Circuit does not belong to the given world");
        }
        if (ops.isEmpty()) {
            return;
        }
        for (CircuitElementChange op : ops) {
            switch (op.kind()) {
                case REMOVE -> removeOne(world, circuit, op.elementId());
                case INSERT -> insertOne(world, circuit, op.registryTypeId(), op.data());
                case CHANGE -> applySync(circuit, op);
            }
        }
    }

    private static void insertOne(ClientWorld world, ClientCircuit circuit, String registryTypeId, CompoundTag data) {
        String inData = data.getString(CoreStrings.ELEMENT_TYPE);
        if (inData != null && !inData.isEmpty() && !registryTypeId.equals(inData)) {
            throw new IllegalArgumentException(
                    "Registry type id mismatch: step " + registryTypeId + ", data " + inData);
        }
        CompoundTag delta = new CompoundTag();
        TagUtil.putUUID(delta, CoreStrings.CIRCUIT_ID, circuit.getId());
        CircuitElement el = CircuitElement.deserialize(data, world);
        if (el instanceof CircuitNode) {
            TagUtil.putCompoundList(delta, CoreStrings.NODES, List.of(data));
        } else if (el instanceof CircuitEdge) {
            TagUtil.putCompoundList(delta, CoreStrings.EDGES, List.of(data));
        } else if (el instanceof CircuitComponent) {
            TagUtil.putCompoundList(delta, CoreStrings.COMPONENTS, List.of(data));
        } else {
            throw new IllegalArgumentException("Unknown element class: " + el.getClass().getName());
        }
        circuit.load(world, delta);
    }

    private static void applySync(ClientCircuit circuit, CircuitElementChange op) {
        CircuitElement el = circuit.findElement(op.elementId());
        if (el == null) {
            throw new IllegalArgumentException("CHANGE target not found: " + op.elementId());
        }
        String expected = op.registryTypeId();
        String actual = CircuitElementChange.registryTypeIdOf(el);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "CHANGE registry type mismatch: expected " + expected + ", element " + actual);
        }
        SyncRegistry.readSyncData(el, op.data());
    }

    private static void removeOne(ClientWorld world, ClientCircuit circuit, UUID id) {
        CircuitElement el = circuit.findElement(id);
        if (el == null) {
            throw new IllegalArgumentException("Unknown or unresolved element id: " + id);
        }
        if (el instanceof CircuitEdge e) {
            world.disconnectWithoutRemoveEvent(e);
            return;
        }
        if (el instanceof CircuitComponent c) {
            c.destroyForTopologyMirror(circuit);
            return;
        }
        if (el instanceof CircuitNode n) {
            world.destroy(n);
            return;
        }
        throw new IllegalArgumentException("Unknown element type: " + el.getClass().getName());
    }
}
