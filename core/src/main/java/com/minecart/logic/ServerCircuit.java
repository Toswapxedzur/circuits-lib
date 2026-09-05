package com.minecart.logic;

import com.minecart.spice.NgSpice;
import com.minecart.spice.SpiceSolver;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side simulation for a {@link Circuit}: linear system, tick, and world integration.
 * Structure and serialization are on {@link Circuit}.
 */
public class ServerCircuit extends Circuit {

    private static final Logger log = LoggerFactory.getLogger(ServerCircuit.class);
    private static final double CURRENT_SYNC_EPSILON = 1e-12;

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

    /**
     * Electrical backend: ngspice (adaptive, error-controlled transient per tick) unless
     * {@code -Dcircuitslib.solver=ejml} asks for the built-in linear solver, or libngspice is missing.
     */
    public static final boolean SPICE_BACKEND =
            !"ejml".equalsIgnoreCase(System.getProperty("circuitslib.solver", "ngspice")) && NgSpice.available();

    private double tickRateOrDefault() {
        ServerWorld w = getWorld();
        return w != null ? w.getTickRate() : 0.05;
    }

    public void tick() {
        post(preTick);
        overpoweredThisTick.clear();
        if (dirty) {
            update();
            dirty = false;
        }

        Map<CircuitEdge, Double> previousCurrents = snapshotCurrents();
        boolean solved = false;
        boolean useBuiltin = true;
        if (SPICE_BACKEND) {
            SpiceSolver.Result r = SpiceSolver.solve(nodes, edges, components, tickRateOrDefault());
            if (r == SpiceSolver.Result.OK) { solved = true; useBuiltin = false; }
            else if (r == SpiceSolver.Result.FAILED) { useBuiltin = false; } // a failed ngspice solve is a real failure: zero, don't mask it
        }
        if (useBuiltin) {
            system.stampRelation(this::collectRelation);
            solved = system.solve();
        }
        if (!solved) {
            log.warn("electrical solve failed for circuit {}; resetting {} nodes and {} edges to zero",
                    getId(), nodes.size(), edges.size());
            resetElectricalVariablesToZero();
        }
        notifyCurrentChanges(previousCurrents);

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
        // Ground exactly one reference node PER connected component. A circuit can hold several
        // disconnected subgraphs (circuits only ever merge, never split), so grounding only the
        // first node of the whole circuit leaves every other component's voltages unconstrained
        // and the matrix singular -> solve() fails -> everything gets zeroed each tick. A degree-0
        // node forms its own component and is grounded too, giving it a well-posed V=0 equation
        // (CircuitNode.collectRule stamps nothing for an isolated, ungrounded node). For the normal
        // single-connected-circuit case this grounds exactly the first node, identical to before.
        Set<CircuitNode> visited = new java.util.HashSet<>();
        for (CircuitNode seed : nodes) {
            if (visited.contains(seed)) {
                continue;
            }
            seed.setGround(true);
            bfs(seed, visited::add, e -> {});
        }
        system.collectVar(this::collectVariable);
        system.init();
    }

    private Map<CircuitEdge, Double> snapshotCurrents() {
        Map<CircuitEdge, Double> previous = new HashMap<>();
        for (CircuitEdge edge : edges) {
            previous.put(edge, edge.getCurrent().getValue());
        }
        return previous;
    }

    private void resetElectricalVariablesToZero() {
        for (CircuitNode node : nodes) {
            node.getVoltage().setValue(0.0);
        }
        for (CircuitEdge edge : edges) {
            edge.getCurrent().setValue(0.0);
        }
    }

    private void notifyCurrentChanges(Map<CircuitEdge, Double> previousCurrents) {
        ServerWorld world = getWorld();
        if (world == null) {
            return;
        }
        for (CircuitEdge edge : edges) {
            double before = previousCurrents.getOrDefault(edge, 0.0);
            double after = edge.getCurrent().getValue();
            if (Math.abs(after - before) > CURRENT_SYNC_EPSILON) {
                world.getLevel().notifyElementChanged(edge);
            }
        }
    }

    public void collectRelation(LinearSystem.RelationProvider provider) {
        for (CircuitNode node : nodes) {
            node.collectRule(provider);
        }
        for (CircuitEdge edge : edges) {
            edge.collectRule(provider);
        }
        // Components contribute their constitutive relations (e.g. BJTransistor's I_C = beta*I_B)
        // on top of the branch/device equations their internal nodes/edges already supply. Without
        // this loop CircuitComponent.collectRule is dead and controlled sources are never enforced.
        for (CircuitComponent component : components) {
            component.collectRule(provider);
        }
    }

    public void collectVariable(Set<DoubleVar> collector) {
        for (CircuitNode node : nodes) {
            node.collectVariable(collector);
        }
        for (CircuitEdge edge : edges) {
            edge.collectVariable(collector);
        }
        // Mirror collectRelation: give components a chance to register any extra variables their
        // constitutive relations reference beyond the internal node/edge variables above.
        for (CircuitComponent component : components) {
            component.collectVariable(collector);
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
}
