package com.minecart.client.payload.server.topology;

import com.minecart.misc.CoreStrings;
import com.minecart.client.logic.ClientCircuit;
import com.minecart.client.logic.ClientLevel;
import com.minecart.client.logic.ClientWorld;
import com.minecart.client.payload.PayloadHandler;
import com.minecart.foundation.Circuit;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.foundation.Level;
import com.minecart.foundation.World;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Applies {@link CircuitTopologyPayload} on a {@link ClientLevel} (server → client replication).
 */
public final class CircuitTopologyHandler implements PayloadHandler<CircuitTopologyPayload> {

    private final ClientLevel level;

    public CircuitTopologyHandler(ClientLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    public ClientLevel getLevel() {
        return level;
    }

    @Override
    public void handle(CircuitTopologyPayload payload) {
        ResolvedWorldCircuit resolved = resolveWorldAndCircuit(level, payload.getWorldId(), payload.getCircuitId());
        applyDelta(resolved.world(), resolved.circuit(), payload.getChanges());
    }

    /**
     * Applies ordered steps when world and circuit are already resolved.
     */
    public static void handle(CircuitTopologyPayload payload, ClientWorld world, ClientCircuit circuit) {
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

    private static void applyDelta(ClientWorld world, ClientCircuit circuit, List<CircuitTopologyChange> ops) {
        if (circuit.getWorld() != world) {
            throw new IllegalArgumentException("Circuit does not belong to the given world");
        }
        if (ops.isEmpty()) {
            return;
        }
        for (CircuitTopologyChange op : ops) {
            if (op.kind() == CircuitTopologyChange.Kind.REMOVE) {
                removeOne(world, circuit, op.elementId());
            } else {
                insertOne(world, circuit, op.elementKind(), op.data());
            }
        }
    }

    private static void insertOne(ClientWorld world, ClientCircuit circuit, CircuitTopologyChange.ElementKind kind, CompoundTag data) {
        CompoundTag delta = new CompoundTag();
        TagUtil.putUUID(delta, CoreStrings.CIRCUIT_ID, circuit.getId());
        switch (kind) {
            case NODE -> TagUtil.putCompoundList(delta, CoreStrings.NODES, List.of(data));
            case EDGE -> TagUtil.putCompoundList(delta, CoreStrings.EDGES, List.of(data));
            case COMPONENT -> TagUtil.putCompoundList(delta, CoreStrings.COMPONENTS, List.of(data));
        }
        circuit.load(world, delta);
    }

    private static void removeOne(ClientWorld world, ClientCircuit circuit, UUID id) {
        removeElements(world, circuit, List.of(id));
    }

    private static void removeElements(ClientWorld world, ClientCircuit circuit, List<UUID> ids) {
        LinkedHashSet<UUID> pending = new LinkedHashSet<>(ids);

        for (UUID id : new ArrayList<>(pending)) {
            CircuitElement el = circuit.findElement(id);
            if (el instanceof CircuitEdge e) {
                world.disconnectWithoutRemoveEvent(e);
                pending.remove(id);
            }
        }
        for (UUID id : new ArrayList<>(pending)) {
            CircuitElement el = circuit.findElement(id);
            if (el instanceof CircuitComponent c) {
                c.destroyForTopologyMirror(circuit);
                pending.remove(id);
            }
        }
        for (UUID id : new ArrayList<>(pending)) {
            CircuitElement el = circuit.findElement(id);
            if (el instanceof CircuitNode n) {
                world.destroy(n);
                pending.remove(id);
            }
        }
        pending.removeIf(id -> circuit.findElement(id) == null);
        if (!pending.isEmpty()) {
            throw new IllegalArgumentException("Unknown or unresolved element ids: " + pending);
        }
    }
}
