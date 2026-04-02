package com.minecart.client.payload.server.topology;

import com.minecart.logic.Circuit;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.Level;
import com.minecart.logic.ServerCircuit;
import com.minecart.logic.ServerWorld;
import com.minecart.logic.World;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.ListTag;
import com.minecart.serialization.tag.Tag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Tag read/write for {@link CircuitTopologyPayload} and ordered application of topology steps on {@link ServerCircuit}.
 */
public final class CircuitTopologyReceiver {

    static final String TAG_WORLD_ID = "world_id";
    static final String TAG_CIRCUIT_ID = "circuit_id";
    static final String TAG_CHANGES = "changes";

    private CircuitTopologyReceiver() {
    }

    static void writePayload(CircuitTopologyPayload p, CompoundTag tag) {
        TagUtil.putUUID(tag, TAG_WORLD_ID, p.worldId);
        TagUtil.putUUID(tag, TAG_CIRCUIT_ID, p.circuitId);
        ListTag list = new ListTag();
        for (CircuitTopologyChange c : p.changes) {
            CompoundTag step = new CompoundTag();
            c.save(step);
            list.add(step);
        }
        tag.put(TAG_CHANGES, list);
    }

    static void readPayload(CircuitTopologyPayload p, CompoundTag tag) {
        p.worldId = TagUtil.getUUID(tag, TAG_WORLD_ID);
        p.circuitId = TagUtil.getUUID(tag, TAG_CIRCUIT_ID);
        if (p.circuitId == null) {
            throw new IllegalArgumentException("Missing '" + TAG_CIRCUIT_ID + "'");
        }
        p.changes.clear();
        Tag t = tag.get(TAG_CHANGES);
        if (t instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                CompoundTag step = TagUtil.requireCompoundTag(list.get(i), TAG_CHANGES + "[" + i + "]");
                p.changes.add(CircuitTopologyChange.load(step));
            }
        }
    }

    static ResolvedWorldCircuit resolveWorldAndCircuit(Level level, UUID worldId, UUID circuitId) {
        if (circuitId == null) {
            throw new IllegalArgumentException("Missing circuit id");
        }
        if (worldId != null) {
            World w = level.findWorld(worldId);
            if (w == null) {
                throw new IllegalArgumentException("No world for id: " + worldId);
            }
            Circuit c = w.findCircuit(circuitId);
            if (c == null) {
                throw new IllegalArgumentException("No circuit for id: " + circuitId + " in world " + worldId);
            }
            return new ResolvedWorldCircuit(w, c);
        }
        Circuit c = level.findCircuit(circuitId);
        if (c == null) {
            throw new IllegalArgumentException("No circuit for id: " + circuitId + " in level");
        }
        World w = findWorldContaining(c, level);
        if (w == null) {
            throw new IllegalArgumentException("Circuit " + circuitId + " is not attached to any world on this level");
        }
        return new ResolvedWorldCircuit(w, c);
    }

    private static World findWorldContaining(Circuit circuit, Level level) {
        for (World w : level.getWorlds()) {
            if (w.getCircuits().contains(circuit)) {
                return w;
            }
        }
        return null;
    }

    record ResolvedWorldCircuit(World world, Circuit circuit) {
    }

    /**
     * Applies topology steps in chronological order: each {@link CircuitTopologyChange.Kind#INSERT} loads one element;
     * each {@link CircuitTopologyChange.Kind#REMOVE} removes one element by id.
     */
    public static void applyDelta(ServerWorld world, ServerCircuit circuit, List<CircuitTopologyChange> ops) {
        if (world == null || circuit == null) {
            throw new IllegalArgumentException("world and circuit must be non-null");
        }
        if (circuit.getWorld() != world) {
            throw new IllegalArgumentException("Circuit does not belong to the given world");
        }
        if (ops == null || ops.isEmpty()) {
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

    private static void insertOne(ServerWorld world, ServerCircuit circuit, CircuitTopologyChange.ElementKind kind, CompoundTag data) {
        CompoundTag delta = new CompoundTag();
        TagUtil.putUUID(delta, "circuit_id", circuit.getId());
        switch (kind) {
            case NODE -> TagUtil.putCompoundList(delta, "nodes", List.of(data));
            case EDGE -> TagUtil.putCompoundList(delta, "edges", List.of(data));
            case COMPONENT -> TagUtil.putCompoundList(delta, "components", List.of(data));
        }
        circuit.load(world, delta);
    }

    private static void removeOne(ServerWorld world, ServerCircuit circuit, UUID id) {
        removeElements(world, circuit, List.of(id));
    }

    private static void removeElements(ServerWorld world, ServerCircuit circuit, List<UUID> ids) {
        LinkedHashSet<UUID> pending = new LinkedHashSet<>(ids);

        for (UUID id : new ArrayList<>(pending)) {
            CircuitElement el = circuit.findElement(id);
            if (el instanceof CircuitEdge e) {
                world.disconnect(e);
                pending.remove(id);
            }
        }
        for (UUID id : new ArrayList<>(pending)) {
            CircuitElement el = circuit.findElement(id);
            if (el instanceof CircuitComponent c) {
                c.destroy(circuit);
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
