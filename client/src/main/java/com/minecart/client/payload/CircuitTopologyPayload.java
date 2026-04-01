package com.minecart.client.payload;

import com.minecart.logic.Circuit;
import com.minecart.logic.CircuitTopology;
import com.minecart.logic.Level;
import com.minecart.logic.ServerCircuit;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.logic.World;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.ListTag;
import com.minecart.serialization.tag.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Network payload for circuit topology changes: removed element ids plus serialized additions
 * (nodes, edges, components) in the same tag shape as {@link com.minecart.logic.Circuit#save}.
 */
public class CircuitTopologyPayload extends Payload {

    public static final String PAYLOAD_ID = "minecart.circuit_topology_payload";

    public static final PayloadType<CircuitTopologyPayload> TYPE =
            PayloadRegistry.register(PAYLOAD_ID, CircuitTopologyPayload::new);

    private static final String TAG_WORLD_ID = "world_id";
    private static final String TAG_CIRCUIT_ID = "circuit_id";
    private static final String TAG_REMOVED_IDS = "removed_ids";
    private static final String TAG_ADDED_NODES = "added_nodes";
    private static final String TAG_ADDED_EDGES = "added_edges";
    private static final String TAG_ADDED_COMPONENTS = "added_components";

    private UUID worldId;
    private UUID circuitId;
    private final List<UUID> removedElementIds = new ArrayList<>();
    private final List<CompoundTag> addedNodes = new ArrayList<>();
    private final List<CompoundTag> addedEdges = new ArrayList<>();
    private final List<CompoundTag> addedComponents = new ArrayList<>();

    public CircuitTopologyPayload() {
    }

    public CircuitTopologyPayload(
            UUID worldId,
            UUID circuitId,
            List<UUID> removedElementIds,
            List<CompoundTag> addedNodes,
            List<CompoundTag> addedEdges,
            List<CompoundTag> addedComponents) {
        this.worldId = worldId;
        this.circuitId = circuitId;
        if (removedElementIds != null) {
            this.removedElementIds.addAll(removedElementIds);
        }
        if (addedNodes != null) {
            this.addedNodes.addAll(addedNodes);
        }
        if (addedEdges != null) {
            this.addedEdges.addAll(addedEdges);
        }
        if (addedComponents != null) {
            this.addedComponents.addAll(addedComponents);
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

    public List<UUID> getRemovedElementIds() {
        return Collections.unmodifiableList(removedElementIds);
    }

    public List<CompoundTag> getAddedNodes() {
        return Collections.unmodifiableList(addedNodes);
    }

    public List<CompoundTag> getAddedEdges() {
        return Collections.unmodifiableList(addedEdges);
    }

    public List<CompoundTag> getAddedComponents() {
        return Collections.unmodifiableList(addedComponents);
    }

    /**
     * Resolves the circuit on {@code level} and applies removals then additions on the server simulation.
     *
     * @throws IllegalArgumentException if ids do not resolve
     * @throws IllegalStateException    if the target is not {@link ServerWorld}/{@link ServerCircuit}
     */
    public void applyTo(ServerLevel level) {
        ResolvedWorldCircuit resolved = resolveWorldAndCircuit(level);
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
        CircuitTopology.applyDelta(sw, sc, removedElementIds, addedNodes, addedEdges, addedComponents);
    }

    /**
     * Applies this delta to an already-resolved server circuit (e.g. single-world editor).
     */
    public void applyTo(ServerWorld world, ServerCircuit circuit) {
        if (circuitId != null && !circuitId.equals(circuit.getId())) {
            throw new IllegalArgumentException("Circuit id mismatch: payload " + circuitId + ", actual " + circuit.getId());
        }
        if (circuit.getWorld() != world) {
            throw new IllegalArgumentException("Circuit does not belong to the given world");
        }
        CircuitTopology.applyDelta(world, circuit, removedElementIds, addedNodes, addedEdges, addedComponents);
    }

    private ResolvedWorldCircuit resolveWorldAndCircuit(Level level) {
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

    private record ResolvedWorldCircuit(World world, Circuit circuit) {
    }

    @Override
    protected void savePayload(CompoundTag tag) {
        TagUtil.putUUID(tag, TAG_WORLD_ID, worldId);
        TagUtil.putUUID(tag, TAG_CIRCUIT_ID, circuitId);
        putUuidList(tag, TAG_REMOVED_IDS, removedElementIds);
        putCompoundList(tag, TAG_ADDED_NODES, addedNodes);
        putCompoundList(tag, TAG_ADDED_EDGES, addedEdges);
        putCompoundList(tag, TAG_ADDED_COMPONENTS, addedComponents);
    }

    @Override
    protected void loadPayload(CompoundTag tag) {
        worldId = TagUtil.getUUID(tag, TAG_WORLD_ID);
        circuitId = TagUtil.getUUID(tag, TAG_CIRCUIT_ID);
        if (circuitId == null) {
            throw new IllegalArgumentException("Missing '" + TAG_CIRCUIT_ID + "'");
        }
        removedElementIds.clear();
        addedNodes.clear();
        addedEdges.clear();
        addedComponents.clear();
        readUuidList(tag, TAG_REMOVED_IDS, removedElementIds);
        readCompoundList(tag, TAG_ADDED_NODES, addedNodes);
        readCompoundList(tag, TAG_ADDED_EDGES, addedEdges);
        readCompoundList(tag, TAG_ADDED_COMPONENTS, addedComponents);
    }

    private static void putUuidList(CompoundTag tag, String key, List<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        ListTag list = new ListTag();
        for (UUID id : ids) {
            if (id != null) {
                list.add(TagUtil.writeUUID(id));
            }
        }
        tag.put(key, list);
    }

    private static void readUuidList(CompoundTag tag, String key, List<UUID> out) {
        Tag t = tag.get(key);
        if (!(t instanceof ListTag list)) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            UUID u = TagUtil.readUUID(list.get(i));
            if (u == null) {
                u = TagUtil.parseUuidStringTag(list.get(i));
            }
            if (u != null) {
                out.add(u);
            }
        }
    }

    private static void putCompoundList(CompoundTag tag, String key, List<CompoundTag> compounds) {
        if (compounds.isEmpty()) {
            return;
        }
        ListTag list = new ListTag();
        for (CompoundTag c : compounds) {
            list.add(c);
        }
        tag.put(key, list);
    }

    private static void readCompoundList(CompoundTag tag, String key, List<CompoundTag> out) {
        Tag t = tag.get(key);
        if (!(t instanceof ListTag list)) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            out.add(TagUtil.requireCompoundTag(list.get(i), key + "[" + i + "]"));
        }
    }
}
