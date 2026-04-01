package com.minecart.logic;

import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.ListTag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Applies incremental topology updates (element removals and additions) to a {@link ServerCircuit}.
 * Used with network payloads that carry the same logical data as {@link Circuit#save}/{@link Circuit#load(World, CompoundTag)}
 * but as a delta.
 */
public final class CircuitTopology {

    private CircuitTopology() {
    }

    /**
     * Removes elements by id (edges first, then {@link CircuitComponent}s, then nodes), then appends new elements
     * using {@link Circuit#load(World, CompoundTag)} with a synthetic tag containing only the added lists.
     *
     * @param world   authoritative world for disconnect/destroy
     * @param circuit must be a {@link ServerCircuit} attached to {@code world}
     */
    public static void applyDelta(
            ServerWorld world,
            ServerCircuit circuit,
            List<UUID> removedElementIds,
            List<CompoundTag> addedNodeTags,
            List<CompoundTag> addedEdgeTags,
            List<CompoundTag> addedComponentTags) {
        if (world == null || circuit == null) {
            throw new IllegalArgumentException("world and circuit must be non-null");
        }
        if (circuit.getWorld() != world) {
            throw new IllegalArgumentException("Circuit does not belong to the given world");
        }
        if (removedElementIds != null && !removedElementIds.isEmpty()) {
            removeElements(world, circuit, removedElementIds);
        }
        if (hasAdds(addedNodeTags, addedEdgeTags, addedComponentTags)) {
            CompoundTag delta = new CompoundTag();
            TagUtil.putUUID(delta, "circuit_id", circuit.getId());
            putList(delta, "nodes", addedNodeTags);
            putList(delta, "edges", addedEdgeTags);
            putList(delta, "components", addedComponentTags);
            circuit.load(world, delta);
        }
    }

    private static boolean hasAdds(
            List<CompoundTag> nodes, List<CompoundTag> edges, List<CompoundTag> components) {
        return (nodes != null && !nodes.isEmpty())
                || (edges != null && !edges.isEmpty())
                || (components != null && !components.isEmpty());
    }

    private static void putList(CompoundTag delta, String key, List<CompoundTag> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        ListTag list = new ListTag();
        for (CompoundTag t : tags) {
            list.add(t);
        }
        delta.put(key, list);
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
