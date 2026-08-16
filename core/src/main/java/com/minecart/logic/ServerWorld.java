package com.minecart.logic;

import com.minecart.event.events.CircuitElementEndpointChangeEvent;
import com.minecart.event.events.CircuitLifecycleEvent;
import com.minecart.event.events.ElementEvent;
import com.minecart.event.events.ElementInfoInjectEvent;
import com.minecart.event.events.ServerTickEvent;
import com.minecart.event.events.ShortCircuitEvent;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.registry.CircuitElementType;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.ElectricalInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-side simulation for a {@link World}: circuit ticks, element creation, and short-circuit events.
 */
public class ServerWorld extends World {

    protected final ServerTickEvent.World preTick = new ServerTickEvent.World(ServerTickEvent.Phase.PRE, this);
    protected final ServerTickEvent.World postTick = new ServerTickEvent.World(ServerTickEvent.Phase.POST, this);
    protected final List<CircuitEdge> overpowered = new ArrayList<>();
    protected final ShortCircuitEvent shortCircuitEvent = new ShortCircuitEvent(this, overpowered);

    /**
     * The snap-circuit baseboard backing this world in {@link com.minecart.foundation.GameMode#SNAP_3D}
     * mode, or {@code null} in 2D mode. When set, the world's circuit is <em>derived</em> from the board
     * (see {@link com.minecart.snap.SnapBoard#rebuild}) rather than edited directly, and persistence saves
     * the board instead of the derived circuits.
     */
    private com.minecart.snap.SnapBoard snapBoard;

    public ServerWorld(ServerLevel level) {
        super(level);
    }

    /**
     * Server world with a stable id (e.g. matching a client or network-assigned {@link World#getId()}).
     */
    public ServerWorld(ServerLevel level, UUID worldId) {
        super(level, worldId);
    }

    @Override
    public ServerLevel getLevel() {
        return (ServerLevel) level;
    }

    /** The snap baseboard backing this world, or {@code null} in 2D mode. */
    public com.minecart.snap.SnapBoard getSnapBoard() {
        return snapBoard;
    }

    /** Attaches (or clears) the snap baseboard. Callers derive the circuit via {@code board.rebuild(this)}. */
    public void setSnapBoard(com.minecart.snap.SnapBoard snapBoard) {
        this.snapBoard = snapBoard;
    }

    @Override
    public void addCircuit(Circuit circuit) {
        super.addCircuit(circuit);
        if (circuit instanceof ServerCircuit sc) {
            sc.setWorld(this);
        }
        post(new CircuitLifecycleEvent.CircuitInsertEvent(this, circuit));
    }

    @Override
    public boolean removeCircuit(Circuit circuit) {
        boolean removed = super.removeCircuit(circuit);
        if (removed) {
            post(new CircuitLifecycleEvent.CircuitRemoveEvent(this, circuit));
        }
        return removed;
    }

    public void tick() {
        post(preTick);
        for (Circuit c : circuits) {
            if (c instanceof ServerCircuit sc) {
                sc.tick();
                for (CircuitEdge e : sc.drainOverpoweredThisTick()) {
                    setOverpowered(e);
                }
            }
        }
        if (!overpowered.isEmpty()) {
            post(shortCircuitEvent);
        }
        overpowered.clear();
        post(postTick);
    }

    public <T extends CircuitNode> T createNode(CircuitElementType<T> type) {
        return createNodeInternal(type, false, null);
    }

    /**
     * Creates a node when building internal structure from {@link CircuitComponent} (allows {@link CircuitElementType#isUnusual()} types).
     */
    protected <T extends CircuitNode> T createNodeForComponent(CircuitElementType<T> type) {
        return createNodeInternal(type, true, null);
    }

    protected <T extends CircuitNode> T createNodeForComponent(ServerCircuit circuit, CircuitElementType<T> type) {
        return createNodeInternal(type, true, circuit);
    }

    private <T extends CircuitNode> T createNodeInternal(
            CircuitElementType<T> type, boolean allowUnusual, ServerCircuit ownerCircuit) {
        T node = type.create(this, allowUnusual);
        node.setWorld(this);
        ServerCircuit circuit = ownerCircuit != null ? ownerCircuit : createCircuit();
        node.setCircuit(circuit);
        post(new ElementInfoInjectEvent(this, node));
        if (post(new ElementEvent.ElementInsertEvent(this, node))) {
            if (ownerCircuit == null) {
                removeCircuit(circuit);
            }
            throw new IllegalStateException("Circuit element insert cancelled");
        }
        circuit.nodes().add(node);
        circuit.markDirty();
        return node;
    }

    /**
     * Like {@link #createNode(CircuitElementType)} but applies {@link ElectricalVariate#set(ElectricalInfo)} after insert.
     */
    public <T extends CircuitNode & ElectricalVariate<O>, O extends ElectricalInfo> T createNode(
            CircuitElementType<T> type, O propertyInfo) {
        T node = createNode(type);
        node.set(propertyInfo);
        return node;
    }

    /**
     * Like {@link #createNodeForComponent(CircuitElementType)} but applies {@link ElectricalVariate#set(ElectricalInfo)} after insert.
     */
    protected <T extends CircuitNode & ElectricalVariate<O>, O extends ElectricalInfo> T createNodeForComponent(
            CircuitElementType<T> type, O propertyInfo) {
        T node = createNodeForComponent(type);
        node.set(propertyInfo);
        return node;
    }

    protected <T extends CircuitNode & ElectricalVariate<O>, O extends ElectricalInfo> T createNodeForComponent(
            ServerCircuit circuit, CircuitElementType<T> type, O propertyInfo) {
        T node = createNodeForComponent(circuit, type);
        node.set(propertyInfo);
        return node;
    }

    /**
     * Creates a {@link CircuitComponent} on {@code circuit}, then applies {@link ElectricalVariate#set(ElectricalInfo)}.
     */
    public <T extends CircuitComponent & ElectricalVariate<O>, O extends ElectricalInfo> T createComponent(
            ServerCircuit circuit, CircuitElementType<T> type, O propertyInfo) {
        T comp = type.create(this, false);
        circuit.addComponent(comp);
        comp.set(propertyInfo);
        post(new ElementInfoInjectEvent(this, comp));
        circuit.markDirty();
        return comp;
    }

    /**
     * Creates a {@link CircuitComponent}, attaches it to a fresh {@link ServerCircuit}, fires the standard
     * insert events, and runs {@link CircuitComponent#generate()} so its internal nodes/edges exist. Used by
     * the server-side placement pipeline when no {@link ElectricalVariate} info is being supplied (e.g. UI
     * "drop a fresh BJT" with default beta).
     */
    public <T extends CircuitComponent> T createComponent(CircuitElementType<T> type) {
        if (type.isUnusual()) {
            throw new IllegalStateException(
                    "Unusual element type '" + type.getTypeId() + "' cannot be created via createComponent");
        }
        T comp = type.create(this, false);
        comp.setWorld(this);
        ServerCircuit circuit = createCircuit();
        circuit.addComponent(comp);
        post(new ElementInfoInjectEvent(this, comp));
        // Generate internal nodes/edges BEFORE posting the component's own InsertEvent. Each
        // newNode/newEdge inside generate() fires its own InsertEvent which the network listener
        // batches into the outgoing payload; emitting them before the component's InsertEvent means
        // the client sees the internals first, so the subsequent component INSERT's load() call can
        // successfully look them up via findNode/findEdge and apply setComponent(this). With the old
        // order the component arrived first, load() couldn't find any of the still-pending internals,
        // and they were never linked back — leaving them visible (no component owner) on the client
        // canvas.
        comp.generate();
        if (post(new ElementEvent.ElementInsertEvent(this, comp))) {
            removeCircuit(circuit);
            throw new IllegalStateException("Component insert cancelled");
        }
        circuit.markDirty();
        return comp;
    }

    protected ServerCircuit createCircuit() {
        ServerCircuit circuit = new ServerCircuit();
        addCircuit(circuit);
        return circuit;
    }

    public <T extends CircuitEdge> T connect(CircuitElementType<T> type, CircuitNode node1, CircuitNode node2) {
        return connectInternal(type, node1, node2, false);
    }

    /**
     * Connects two nodes with an edge when building from {@link CircuitComponent} (allows {@link CircuitElementType#isUnusual()} types).
     */
    protected <T extends CircuitEdge> T connectInComponent(CircuitElementType<T> type, CircuitNode node1, CircuitNode node2) {
        return connectInternal(type, node1, node2, true);
    }

    protected <T extends CircuitEdge> T connectInternal(
            CircuitElementType<T> type, CircuitNode node1, CircuitNode node2, boolean allowUnusual) {
        if (node1.getWorld() != this || node2.getWorld() != this) {
            throw new IllegalArgumentException("Can't coonect node that doesn't belong to the current ServerWorld");
        }
        if (node1 == node2) {
            throw new IllegalArgumentException("connect cannot create a self-loop");
        }
        T edge = type.create(this, allowUnusual);
        edge.setWorld(this);
        if (!edge.connect(node1, node2, true)) {
            return null;
        }
        edge.connect(node1, node2, false);
        if (!node1.connectEdge(edge, true) || !node2.connectEdge(edge, true)) {
            return null;
        }
        node1.connectEdge(edge, false);
        node2.connectEdge(edge, false);
        post(new ElementInfoInjectEvent(this, edge));
        if (post(new ElementEvent.ElementInsertEvent(this, edge))) {
            node1.disconnect(edge, false);
            node2.disconnect(edge, false);
            edge.disconnect(false);
            return null;
        }
        Circuit circuit1 = node1.getCircuit();
        Circuit circuit2 = node2.getCircuit();
        if (circuit1 != circuit2) {
            circuit2.mergeInto(circuit1);
            removeCircuit(circuit2);
        }
        circuit1.addEdge(edge);
        ((ServerCircuit) circuit1).markDirty();
        return edge;
    }

    /**
     * Like {@link #connect(CircuitElementType, CircuitNode, CircuitNode)} but applies {@link ElectricalVariate#set(ElectricalInfo)}
     * after the edge is placed.
     */
    public <T extends CircuitEdge & ElectricalVariate<O>, O extends ElectricalInfo> T connect(
            CircuitElementType<T> type, CircuitNode node1, CircuitNode node2, O propertyInfo) {
        T edge = connect(type, node1, node2);
        if (edge != null) {
            edge.set(propertyInfo);
        }
        return edge;
    }

    /**
     * Like {@link #connectInComponent(CircuitElementType, CircuitNode, CircuitNode)} but applies {@link ElectricalVariate#set(ElectricalInfo)}.
     */
    protected <T extends CircuitEdge & ElectricalVariate<O>, O extends ElectricalInfo> T connectInComponent(
            CircuitElementType<T> type, CircuitNode node1, CircuitNode node2, O propertyInfo) {
        T edge = connectInComponent(type, node1, node2);
        if (edge != null) {
            edge.set(propertyInfo);
        }
        return edge;
    }

    public boolean disconnect(CircuitEdge edge) {
        if (post(new ElementEvent.ElementRemoveEvent(this, edge))) {
            return false;
        }
        return disconnectWithoutRemoveEvent(edge);
    }

    /**
     * Same as {@link #disconnect(CircuitEdge)} but without firing {@link ElementRemoveEvent}
     * (used when removing edges as part of destroying a node).
     */
    boolean disconnectWithoutRemoveEvent(CircuitEdge edge) {
        CircuitNode node1 = edge.getConnection(0);
        CircuitNode node2 = edge.getConnection(1);
        if (!node1.disconnect(edge, true) || !node2.disconnect(edge, true) || !edge.disconnect(true)) {
            return false;
        }
        node1.disconnect(edge, false);
        if (node2 != node1) {
            node2.disconnect(edge, false);
        }
        edge.disconnect(false);
        Circuit circuit = edge.getCircuit();
        if (circuit != null) {
            circuit.edges().remove(edge);
            if (circuit instanceof ServerCircuit sc) {
                sc.markDirty();
            }
            if (circuit.nodes().isEmpty() && circuit.edges().isEmpty() && circuit.components().isEmpty()) {
                removeCircuit(circuit);
            }
        }
        return true;
    }

    /**
     * Removes a free {@link CircuitNode} from its circuit, plus every non-component wire incident to it.
     *
     * <p>Each incident wire gets its own {@link ElementRemoveEvent} so the client mirror sees a REMOVE
     * delta for the wire instead of inheriting an orphan reference. Removing a wire never splits its
     * circuit: disconnected subgraphs stay in one circuit for persistence / replication stability
     * (per-component grounding in {@link ServerCircuit} keeps the solve well-posed). Direct removal
     * keeps node and circuit identities aligned with whatever the listener captured at REMOVE time.
     *
     * <p>Edges that are part of a {@link com.minecart.logic.CircuitComponent}'s internal star graph are
     * skipped here — those are owned by the component and only {@link CircuitComponent#destroy} is
     * authorised to take them out (see the hasComponent guards on {@link CircuitEdge#disconnect}).
     */
    public boolean destroy(CircuitNode node) {
        if (node.getWorld() != this) {
            throw new IllegalArgumentException("Can't connect node that doesn't belong to the current ServerWorld");
        }

        ServerCircuit circuit = (ServerCircuit) node.getCircuit();
        if (circuit == null || !circuit.nodes().contains(node)) {
            return false;
        }

        if (post(new ElementEvent.ElementRemoveEvent(this, node))) {
            return false;
        }

        for (CircuitEdge edge : new ArrayList<>(node.getConnection())) {
            if (edge.hasComponent()) {
                continue;
            }
            post(new ElementEvent.ElementRemoveEvent(this, edge));
            Circuit ec = edge.getCircuit();
            if (ec != null) {
                ec.edges().remove(edge);
            }
            CircuitNode start = edge.getStart();
            CircuitNode end = edge.getEnd();
            if (start != null) {
                start.disconnect(edge, false);
            }
            if (end != null && end != start) {
                end.disconnect(edge, false);
            }
            edge.disconnect(false);
        }

        circuit.nodes().remove(node);
        circuit.markDirty();

        if (circuit.nodes().isEmpty() && circuit.edges().isEmpty() && circuit.components().isEmpty()) {
            removeCircuit(circuit);
        }

        return true;
    }

    public void setOverpowered(CircuitEdge edge) {
        if (edge.getWorld() != this) {
            throw new IllegalArgumentException("Short circuited in another world");
        }
        overpowered.add(edge);
    }

    /**
     * Repoints {@code edge} to {@code newStart} and {@code newEnd}, taking it off whichever endpoint(s)
     * are being replaced. Either argument may equal the current matching endpoint to mean "leave that
     * side untouched"; passing both as the current values is a no-op.
     *
     * <p>Topology bookkeeping mirrors {@link #connect}/{@link #disconnectWithoutRemoveEvent} but bypasses
     * the {@code hasComponent()} guards on {@link CircuitEdge}, so this method is also the right path for
     * shifting an internal-port edge during a node combine — the standard wire-management API would
     * silently refuse. After the swap:
     *
     * <ol>
     *     <li>Detaching from a former endpoint never splits the old circuit: disconnected subgraphs
     *         are left in one circuit for persistence / replication stability, and {@link ServerCircuit}
     *         grounds one reference node per connected component so the solve stays well-posed.</li>
     *     <li>If the new endpoints sit in different circuits than each other, the smaller side is merged
     *         into the larger via {@link Circuit#mergeInto}, firing rebind events.</li>
     *     <li>The edge itself is moved to whichever circuit its new endpoints share.</li>
     *     <li>{@link CircuitElementEndpointChangeEvent} is posted; the listener catches it and marks the
     *         edge as {@code changed} so the resulting CHANGE op carries the new endpoint UUIDs in
     *         serialized form, which the client mirror's {@code CircuitEdge.load} resync branch consumes.</li>
     *     <li>{@link Level#notifyElementChanged} is called as a belt-and-braces hand-off in case any
     *         downstream listener bypasses the event subscription.</li>
     * </ol>
     *
     * @return {@code true} if anything actually changed; {@code false} if both new endpoints already
     *         match the current ones (no-op).
     * @throws IllegalArgumentException if the edge or either node belongs to a different world, or if
     *         {@code newStart == newEnd} (self-loops are rejected to mirror {@link #connect}).
     */
    public boolean changeEdgeEndpoint(CircuitEdge edge, CircuitNode newStart, CircuitNode newEnd) {
        if (edge == null || newStart == null || newEnd == null) {
            throw new IllegalArgumentException("edge, newStart and newEnd must be non-null");
        }
        if (edge.getWorld() != this || newStart.getWorld() != this || newEnd.getWorld() != this) {
            throw new IllegalArgumentException("Edge or endpoint nodes don't belong to this ServerWorld");
        }
        if (newStart == newEnd) {
            throw new IllegalArgumentException("changeEdgeEndpoint cannot create a self-loop");
        }
        CircuitNode oldStart = edge.getStart();
        CircuitNode oldEnd = edge.getEnd();
        if (oldStart == newStart && oldEnd == newEnd) {
            return false;
        }
        ServerCircuit edgeCircuit = (ServerCircuit) edge.getCircuit();
        if (edgeCircuit == null) {
            throw new IllegalStateException("Edge has no owning circuit");
        }

        // Direct swap on the edge + both endpoint connection sets. Bypass the hasComponent guard on
        // CircuitEdge.connect/disconnect: those exist to lock out user-driven wiring tools from cutting
        // a component's internal star, but this method is the explicit topology-edit API and has already
        // validated the move.
        edge.replaceEndpointsBypassingGuards(newStart, newEnd);

        // Merge: if the new endpoints sit in different ownership circuits, fuse them into a
        // single container and move the edge along. Circuits are no longer split on disconnect:
        // disconnected subgraphs may remain in one circuit for persistence/replication stability.
        ServerCircuit startCircuit = (ServerCircuit) newStart.getCircuit();
        ServerCircuit endCircuit = (ServerCircuit) newEnd.getCircuit();
        ServerCircuit edgeNewCircuit;
        if (startCircuit == endCircuit) {
            edgeNewCircuit = startCircuit;
        } else {
            // Merge endCircuit into startCircuit. Direction is arbitrary; choosing one consistently
            // keeps element-id ordering deterministic across runs which helps tests.
            endCircuit.mergeInto(startCircuit);
            removeCircuit(endCircuit);
            edgeNewCircuit = startCircuit;
        }

        if (edgeCircuit != edgeNewCircuit) {
            edgeCircuit.edges().remove(edge);
            edgeNewCircuit.addEdge(edge);
            post(new com.minecart.event.events.ElementCircuitChangedEvent(this, edge, edgeCircuit, edgeNewCircuit));
        }
        edgeNewCircuit.markDirty();

        post(new CircuitElementEndpointChangeEvent(this, edge, oldStart, oldEnd, newStart, newEnd));
        getLevel().notifyElementChanged(edge);

        if (edgeCircuit != edgeNewCircuit
                && edgeCircuit.nodes().isEmpty()
                && edgeCircuit.edges().isEmpty()
                && edgeCircuit.components().isEmpty()) {
            removeCircuit(edgeCircuit);
        }
        return true;
    }

    /**
     * Convenience server-side combine. Used directly by tests and editor flows that prefer a single
     * call over assembling the same sequence of {@link #changeEdgeEndpoint} / {@link CircuitComponent#replacePort}
     * / {@link #destroy(CircuitNode)} steps. The wire protocol still routes the same sequence as
     * granular client→server payloads (see {@code EdgeEndpointChangePayload},
     * {@code ReplaceComponentNodePayload}, and {@code DeleteElementPayload}) so both code paths stay
     * symmetric.
     *
     * <p>Validation rules:
     * <ul>
     *     <li>Both nodes must mutually accept the merge via {@link CircuitNode#canCombine(CircuitNode)}.
     *         The default implementation rejects non-port internals — subclass overrides may tighten
     *         further.</li>
     *     <li>If {@code absorbed} is a registered port of a component, {@code survivor}'s registry
     *         type id must match (otherwise the port slot would silently change kind).</li>
     *     <li>Multi-component port-on-port combines (both nodes ports of <em>different</em> components)
     *         are rejected because a single node can only sit in one component's port map; resolving
     *         the conflict cleanly is out of scope for the editor's current needs.</li>
     * </ul>
     *
     * <p>Operation order:
     * <ol>
     *     <li>If {@code absorbed} is a port, swap the slot via {@link CircuitComponent#replacePort}
     *         <em>first</em> so {@code survivor} becomes part of the component before any internal
     *         edge is rerouted onto it.</li>
     *     <li>For every edge incident to {@code absorbed}: edges whose other endpoint is
     *         {@code survivor} are deleted (would become self-loops) and the rest are repointed to
     *         {@code survivor} via {@link #changeEdgeEndpoint}.</li>
     *     <li>{@code absorbed} is destroyed via {@link #destroy(CircuitNode)} (its component pointer
     *         was cleared in step 1 if it was a port, so this hits the free-node path).</li>
     * </ol>
     *
     * @return {@code true} if the combine succeeded; {@code false} if any precondition rejected it.
     */
    public boolean combineNodes(CircuitNode survivor, CircuitNode absorbed) {
        if (survivor == null || absorbed == null || survivor == absorbed) {
            return false;
        }
        if (survivor.getWorld() != this || absorbed.getWorld() != this) {
            return false;
        }
        if (!survivor.canCombine(absorbed) || !absorbed.canCombine(survivor)) {
            return false;
        }

        // Direction policy: when exactly one side is a registered port of a component, the port
        // must be the survivor regardless of caller-passed argument order. Anchoring is rigid (port
        // positions are stamped from ComponentAnchorRegistry offsets of the component's centre), so
        // letting a free node "win" the slot would yank the port off its anchor and leave the
        // component's internal struts stretched to the cursor's drop position — see the BJT + free
        // resistor node bug. Swapping locally instead of returning false keeps both call orderings
        // (drag-source-as-survivor vs. drop-target-as-survivor) doing the geometrically correct
        // thing.
        CircuitComponent survivorComp = survivor.getComponent();
        boolean survivorIsPort = survivorComp != null && survivorComp.isPort(survivor);
        CircuitComponent absorbedComp = absorbed.getComponent();
        boolean absorbedIsPort = absorbedComp != null && absorbedComp.isPort(absorbed);
        if (absorbedIsPort && !survivorIsPort) {
            CircuitNode tmp = survivor;
            survivor = absorbed;
            absorbed = tmp;
            survivorComp = survivor.getComponent();
            survivorIsPort = true;
            absorbedComp = absorbed.getComponent();
            absorbedIsPort = false;
        }

        // After the swap above, absorbed is never a port of its own component while survivor is a
        // port of another. The "absorbed is still a port" leg only fires for the exotic port-on-port
        // case (both nodes on the same component, e.g. an editor flow that walks two ports of one
        // BJT into each other — currently allowed by the type/component guard below). Cross-component
        // port-on-port is left for the Phase 2b cascade engine.
        if (absorbedIsPort) {
            if (!java.util.Objects.equals(survivor.getRegistryTypeId(), absorbed.getRegistryTypeId())) {
                return false;
            }
            if (survivorComp != null && survivorComp != absorbedComp) {
                return false;
            }
        }

        if (absorbedIsPort) {
            int idx = absorbedComp.portIndexOf(absorbed);
            if (idx < 0) {
                return false;
            }
            if (!absorbedComp.switchPort(idx, survivor)) {
                return false;
            }
        }

        for (CircuitEdge incident : new ArrayList<>(absorbed.getConnection())) {
            CircuitNode other = incident.getOther(absorbed);
            if (other == survivor) {
                // Self-loop on survivor — drop the wire instead of carrying it. The component-internal
                // analogue would never get here because intrinsic non-port internals can't be the
                // survivor (canCombine refuses them).
                if (incident.hasComponent()) {
                    // Internal edge of a component now collapses; bypass the hasComponent guard the same
                    // way we do during component teardown.
                    incident.setComponent(null);
                }
                disconnect(incident);
                continue;
            }
            CircuitNode newStart = incident.getStart() == absorbed ? survivor : incident.getStart();
            CircuitNode newEnd = incident.getEnd() == absorbed ? survivor : incident.getEnd();
            changeEdgeEndpoint(incident, newStart, newEnd);
        }

        return destroy(absorbed);
    }
}
