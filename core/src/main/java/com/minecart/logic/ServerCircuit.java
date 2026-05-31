package com.minecart.logic;

import com.minecart.misc.CoreStrings;
import com.minecart.event.events.Event;
import com.minecart.event.events.ServerTickEvent;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.math.DoubleVar;
import com.minecart.math.LinearSystem;
import com.minecart.registry.CircuitElementType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side simulation for a {@link Circuit}: linear system, tick, and world integration.
 * Structure and serialization are on {@link Circuit}.
 */
public class ServerCircuit extends Circuit {

    protected boolean dirty;
    protected LinearSystem system;

    /** Edges that transitioned into overpowered this tick; drained by {@link ServerWorld} after {@link #tick()}. */
    protected final List<CircuitEdge> overpoweredThisTick = new ArrayList<>();

    protected final ServerTickEvent.Circuit preTick = new ServerTickEvent.Circuit(ServerTickEvent.Phase.PRE, this);
    protected final ServerTickEvent.Circuit postTick = new ServerTickEvent.Circuit(ServerTickEvent.Phase.POST, this);

    /**
     * Invoke when the electrical model or topology must be rebuilt (new variables / relations).
     */
    public void markDirty() {
        this.dirty = true;
    }

    @Override
    public ServerWorld getWorld() {
        World w = super.getWorld();
        if (w != null && !(w instanceof ServerWorld)) {
            throw new IllegalStateException("ServerCircuit is bound to a non-server world: " + w.getClass().getName());
        }
        return (ServerWorld) w;
    }

    @Override
    public void setWorld(World world) {
        if (world != null && !(world instanceof ServerWorld)) {
            throw new IllegalArgumentException("ServerCircuit requires ServerWorld");
        }
        super.setWorld(world);
    }

    public void setWorld(ServerWorld world) {
        super.setWorld(world);
    }

    /**
     * Creates a node in this world's graph (delegates to {@link ServerWorld#createNode}).
     * Call from circuit-level orchestration (e.g. {@link CircuitComponent} during {@link CircuitComponent#generate()}) instead of reaching the world from an element.
     */
    public <T extends CircuitNode> T createNode(CircuitElementType<T> type) {
        ServerWorld w = getWorld();
        if (w == null) {
            throw new IllegalStateException("ServerCircuit has no world");
        }
        return w.createNode(type);
    }

    /**
     * Connects two nodes with an edge (delegates to {@link ServerWorld#connect}).
     * @see #createNode(CircuitElementType)
     */
    public <T extends CircuitEdge> T connect(CircuitElementType<T> type, CircuitNode node1, CircuitNode node2) {
        ServerWorld w = getWorld();
        if (w == null) {
            throw new IllegalStateException("ServerCircuit has no world");
        }
        return w.connect(type, node1, node2);
    }

    /**
     * Moves edges reported as newly overpowered this tick to the caller; list is cleared.
     */
    public List<CircuitEdge> drainOverpoweredThisTick() {
        if (overpoweredThisTick.isEmpty()) {
            return List.of();
        }
        List<CircuitEdge> copy = new ArrayList<>(overpoweredThisTick);
        overpoweredThisTick.clear();
        return copy;
    }

    public ServerCircuit() {
        super(UUID.randomUUID());
        system = new LinearSystem();
        dirty = false;
    }

    public ServerCircuit(UUID id) {
        super(id);
        system = new LinearSystem();
        dirty = false;
    }

    public void tick() {
        post(preTick);
        overpoweredThisTick.clear();
        if (dirty) {
            update();
            dirty = false;
        }

        system.stampRelation(this::collectRelation);
        system.solve();

        for (CircuitNode node : nodes) {
            node.tick();
        }
        for (CircuitEdge edge : edges) {
            if (!edge.isOverpowered() && edge.shortCircuit()) {
                overpoweredThisTick.add(edge);
            }
            edge.tick();
        }
        post(postTick);
    }

    public boolean destroy(CircuitNode node, boolean simulate) {
        if (simulate) {
            return this.nodes.contains(node);
        }

        ServerWorld w = getWorld();
        if (w == null) {
            throw new IllegalStateException("ServerCircuit has no world");
        }
        for (CircuitEdge edge : new java.util.ArrayList<>(node.getConnection())) {
            w.disconnectWithoutRemoveEvent(edge);
        }

        this.nodes.remove(node);
        this.markDirty();
        return true;
    }

    @Override
    public boolean destroyNodeForTopologyMirror(CircuitNode node) {
        return destroy(node, false);
    }

    protected void update() {
        for (CircuitNode node : nodes) {
            node.setGround(false);
        }
        if (!nodes.isEmpty()) {
            nodes.iterator().next().setGround(true);
        }
        system.collectVar(this::collectVariable);
        system.init();
    }

    public void collectRelation(LinearSystem.RelationProvider provider) {
        for (CircuitNode node : nodes) {
            node.collectRule(provider);
        }
        for (CircuitEdge edge : edges) {
            edge.collectRule(provider);
        }
        // Components currently own internal nodes/edges for persistence and rendering. Their
        // constitutive behavior must be expressed through those internal edges until the linear
        // system supports extra component equations without becoming overdetermined.
    }

    public void collectVariable(Set<DoubleVar> collector) {
        for (CircuitNode node : nodes) {
            node.collectVariable(collector);
        }
        for (CircuitEdge edge : edges) {
            edge.collectVariable(collector);
        }
    }

    public boolean post(Event event) {
        return world.post(event);
    }

    public static ServerCircuit loadFromTag(ServerWorld world, CompoundTag tag) {
        UUID circuitId = TagUtil.getUUID(tag, CoreStrings.CIRCUIT_ID);
        if (circuitId == null) {
            throw new IllegalArgumentException("Missing '" + CoreStrings.CIRCUIT_ID + "'");
        }
        ServerCircuit circuit = new ServerCircuit(circuitId);
        world.addCircuit(circuit);
        circuit.load(world, tag);
        return circuit;
    }

    @Override
    public void load(World world, CompoundTag tag) {
        if (world != null && !(world instanceof ServerWorld)) {
            throw new IllegalArgumentException("ServerCircuit requires ServerWorld for load");
        }
        super.load(world, tag);
        markDirty();
    }

    @Override
    public boolean seperate(CircuitNode node1, CircuitNode node2, CircuitEdge edge, Circuit newCircuit) {
        boolean split = super.seperate(node1, node2, edge, newCircuit);
        if (!split) {
            markDirty();
            return false;
        }
        markDirty();
        ((ServerCircuit) newCircuit).markDirty();
        return true;
    }
}
