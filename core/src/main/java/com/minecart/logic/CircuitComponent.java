package com.minecart.logic;

import com.minecart.math.LinearSystem;
import com.minecart.misc.CoreStrings;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.ElectricalInfo;
import com.minecart.variant.info.LockInfo;
import com.minecart.variant.info.LockMode;
import com.minecart.variant.info.LockState;
import com.minecart.variant.info.PositionInfo;
import com.minecart.event.events.ElementEvent;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.logic.cascade.CombineCascadeEngine;
import com.minecart.registry.CircuitElementType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.ListTag;
import com.minecart.serialization.tag.Tag;
import com.minecart.ui.panel.InfoPanelRegistry;
import com.minecart.ui.panel.fields.DropdownSpec;
import com.minecart.ui.panel.fields.LabelSpec;
import com.minecart.ui.panel.fields.NumberFieldSpec;
import com.minecart.variant.info.RotationInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A group of nodes and edges that constitute a circuit component, which can provide extra relations
 * (constitutive equations) on top of the standard branch / device equations.
 *
 * <h2>Ports vs. internal elements</h2>
 * The {@link #nodes} / {@link #edges} sets hold every internal element. A subset of the nodes are
 * <em>ports</em>: nodes deliberately exposed for external wiring. Ports are tracked in
 * {@link #portsByIndex} via {@link #newNode(CircuitElementType, int)} (or one of its electrical-info
 * overloads). Anything else in {@link #nodes} / {@link #edges} is considered <em>internal</em> — not
 * exposed to user-driven wiring (the server's connect handler rejects edges that target a non-port
 * internal node) and not drawn on the canvas (the client's renderer skips them). The base class
 * {@link #getPort(int)} reads the map; subclasses no longer need to override it just to thread index →
 * field switches.
 */
public non-sealed class CircuitComponent extends CircuitElement {
    protected Set<CircuitNode> nodes;
    protected Set<CircuitEdge> edges;
    /**
     * Public port nodes keyed by stable port index. {@code LinkedHashMap} so iteration order is the
     * registration order ({@link #newNode(CircuitElementType, int)} calls inside {@link #generate()}),
     * which keeps {@link #save(CompoundTag)} output and the matching test assertions deterministic.
     */
    protected Map<Integer, CircuitNode> portsByIndex;

    public CircuitComponent(){
        super();
        nodes = new LinkedHashSet<>();
        edges = new LinkedHashSet<>();
        portsByIndex = new LinkedHashMap<>();
    }

    @Override
    public void setCircuit(Circuit circuit) {
        super.setCircuit(circuit);
        if (circuit != null && circuit.getWorld() != null) {
            setWorld(circuit.getWorld());
        }
    }

    /**
     * Extra constitutive relations beyond branch/device equations (e.g. controlled sources). Default: none.
     */
    public void collectRule(LinearSystem.RelationProvider equations) {
    }

    /**
     * Generate the local nodes and edges, not connected to the outside.
     * Subclasses should populate the 'nodes' and 'edges' sets here.
     */
    public void generate(){
    }

    // 1. Basic Node Creation
    protected <T extends CircuitNode> T newNode(CircuitElementType<T> type){
        ServerWorld serverWorld = (ServerWorld) getWorld();
        T node = serverWorld.createNodeForComponent(type);
        nodes.add(node);
        node.setComponent(this);
        return node;
    }

    protected <T extends CircuitNode & ElectricalVariate<O>, O extends ElectricalInfo> T newNode(
            CircuitElementType<T> type, O propertyInfo) {
        ServerWorld serverWorld = (ServerWorld) getWorld();
        T node = serverWorld.createNodeForComponent(type, propertyInfo);
        nodes.add(node);
        node.setComponent(this);
        return node;
    }

    protected <T extends CircuitNode & ElectricalVariate<?>> T newNode(
            CircuitElementType<T> type, int propertyIndex, Object property) {
        ServerWorld serverWorld = (ServerWorld) getWorld();
        T node = serverWorld.createNodeForComponent(type);
        node.set(propertyIndex, property);
        nodes.add(node);
        node.setComponent(this);
        return node;
    }

    // 2. Port Node Creation — same as the basic forms but additionally registers the new node as a
    // public port at {@code portIndex}. Use these from {@link #generate()} for nodes that external wires
    // are allowed to attach to; auxiliary internals (junctions used purely for constitutive equations)
    // should keep using the un-indexed forms above so the connect handler / renderer treat them as
    // hidden.

    /** {@link #newNode(CircuitElementType)} + register as port {@code portIndex}. */
    protected <T extends CircuitNode> T newNode(CircuitElementType<T> type, int portIndex) {
        T node = newNode(type);
        registerPort(portIndex, node);
        return node;
    }

    /** {@link #newNode(CircuitElementType, ElectricalInfo)} + register as port {@code portIndex}. */
    protected <T extends CircuitNode & ElectricalVariate<O>, O extends ElectricalInfo> T newNode(
            CircuitElementType<T> type, int portIndex, O propertyInfo) {
        T node = newNode(type, propertyInfo);
        registerPort(portIndex, node);
        return node;
    }

    /**
     * Inserts {@code node} into {@link #portsByIndex} at {@code portIndex}. Throws on duplicate so
     * a {@code generate()} bug that registers the same index twice fails loudly instead of silently
     * overwriting an earlier port.
     */
    protected void registerPort(int portIndex, CircuitNode node) {
        if (portsByIndex.putIfAbsent(portIndex, node) != null) {
            throw new IllegalStateException(
                    "Port " + portIndex + " already registered on component " + getRegistryTypeId());
        }
    }

    /**
     * Atomically swaps {@code oldPort} for {@code newPort} at {@code portIndex} on this component:
     * the new node takes over the port slot, joins {@link #nodes}, and gains a back-pointer to this
     * component while the old node is unlinked from both. Used by the node-combine path when the
     * absorbed node was a registered port (the editor hands off the slot to the dragged "survivor"
     * node so external wires remain pinned to the same anchor without needing per-edge re-routing).
     *
     * <p>Edge re-routing is intentionally NOT done here — callers (server-side combine handler / test
     * harness) issue per-edge {@code changeEdgeEndpoint} calls explicitly so the protocol stays a flat
     * sequence of granular events instead of one composite "replace + rewire" op the client would have
     * to reconstruct in pieces. The order is also caller-controlled: do this first so {@code newPort}
     * is part of {@code this} before any internal-edge endpoint change moves an internal-junction wire
     * to it; otherwise that wire would briefly connect a junction in {@code this} to a node outside it.
     *
     * <p>Throws if {@code oldPort} isn't actually the port at {@code portIndex} or {@code newPort}
     * is already a port (different index) of this component — those would silently corrupt the
     * port-by-index map.
     */
    public void replacePort(int portIndex, CircuitNode oldPort, CircuitNode newPort) {
        if (oldPort == null || newPort == null) {
            throw new IllegalArgumentException("oldPort and newPort must be non-null");
        }
        if (oldPort == newPort) {
            return;
        }
        CircuitNode existing = portsByIndex.get(portIndex);
        if (existing != oldPort) {
            throw new IllegalStateException(
                    "Port " + portIndex + " on component " + getRegistryTypeId()
                            + " is bound to a different node than oldPort");
        }
        for (Map.Entry<Integer, CircuitNode> e : portsByIndex.entrySet()) {
            if (e.getKey() != portIndex && e.getValue() == newPort) {
                throw new IllegalStateException(
                        "newPort is already bound to port " + e.getKey()
                                + " on component " + getRegistryTypeId());
            }
        }
        portsByIndex.put(portIndex, newPort);
        nodes.remove(oldPort);
        nodes.add(newPort);
        // Multi-owner aware: only drop our own back-pointer from {@code oldPort} so it can remain
        // a port of any other component that was sharing it (Phase 2b cascade may leave a node in
        // several ports' slots). Symmetrically, add ourselves to {@code newPort}'s owner set
        // without disturbing the others.
        oldPort.removeComponent(this);
        newPort.addComponent(this);
    }

    /**
     * Slot-swap primitive: replaces whichever node currently occupies {@code portIndex} with
     * {@code newNode}. Validates same {@link CircuitElement#getRegistryTypeId() registry type id}
     * (a port carries a fixed kind — swapping in a node of a different type would silently change
     * what this component is wired to expect), then delegates to {@link #replacePort}.
     *
     * <p>Intentionally narrow: no incident-edge rerouting, no destruction of the old occupant. The
     * caller (combine engine, panel-driven topology edit, or test) is responsible for those steps
     * so the granular protocol stays a flat sequence of {@code switchPort} / {@code changeEdgeEndpoint}
     * / {@code destroy} ops rather than one composite the client has to reconstruct piecewise.
     *
     * @return {@code true} on success; {@code false} if {@code newNode} is {@code null}, no port
     *         currently sits at {@code portIndex}, the type ids don't match, or any
     *         {@link #replacePort} invariant rejects the swap.
     */
    public boolean switchPort(int portIndex, CircuitNode newNode) {
        if (newNode == null) {
            return false;
        }
        CircuitNode existing = portsByIndex.get(portIndex);
        if (existing == null) {
            return false;
        }
        if (existing == newNode) {
            return true;
        }
        if (!java.util.Objects.equals(existing.getRegistryTypeId(), newNode.getRegistryTypeId())) {
            return false;
        }
        try {
            replacePort(portIndex, existing, newNode);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            return false;
        }
        return true;
    }

    /**
     * @return the port index {@code node} occupies on this component, or {@code -1} if {@code node}
     *         isn't a port. Iteration over {@link #portsByIndex} is in registration order so the
     *         first-match semantics align with {@link #getPort(int)} lookup.
     */
    public int portIndexOf(CircuitNode node) {
        if (node == null) {
            return -1;
        }
        for (Map.Entry<Integer, CircuitNode> e : portsByIndex.entrySet()) {
            if (e.getValue() == node) {
                return e.getKey();
            }
        }
        return -1;
    }

    // 3. Basic Edge Creation
    protected <T extends CircuitEdge> T newEdge(CircuitElementType<T> type, CircuitNode node1, CircuitNode node2){
        ServerWorld serverWorld = (ServerWorld) getWorld();
        T edge = serverWorld.connectInComponent(type, node1, node2);
        edges.add(edge);
        edge.setComponent(this);
        return edge;
    }

    protected <T extends CircuitEdge & ElectricalVariate<O>, O extends ElectricalInfo> T newEdge(
            CircuitElementType<T> type, CircuitNode node1, CircuitNode node2, O propertyInfo) {
        ServerWorld serverWorld = (ServerWorld) getWorld();
        T edge = serverWorld.connectInComponent(type, node1, node2, propertyInfo);
        edges.add(edge);
        edge.setComponent(this);
        return edge;
    }

    protected <T extends CircuitEdge & ElectricalVariate<?>> T newEdge(
            CircuitElementType<T> type, CircuitNode node1, CircuitNode node2, int propertyIndex, Object property) {
        ServerWorld serverWorld = (ServerWorld) getWorld();
        T edge = serverWorld.connectInComponent(type, node1, node2);
        if (edge != null) {
            edge.set(propertyIndex, property);
        }
        edges.add(edge);
        edge.setComponent(this);
        return edge;
    }

    /**
     * Maps an external connection index (e.g., 0 for North side, 1 for South side) to the specific
     * internal {@link CircuitNode} acting as that port. Public so callers (renderer, server-side
     * placement handlers, anchor wiring, connect handler) can look up a port without going through
     * {@link #connect}. The default implementation reads {@link #portsByIndex}; subclasses populated
     * via {@link #newNode(CircuitElementType, int)} should not need to override this.
     */
    public CircuitNode getPort(int index){
        return portsByIndex.get(index);
    }

    /**
     * @return {@code true} if {@code node} is a registered port of this component (any index).
     *         A non-port internal node (e.g. a junction used only for constitutive equations) returns
     *         {@code false} even though {@code node.getComponent() == this}.
     */
    public boolean isPort(CircuitNode node) {
        if (node == null) {
            return false;
        }
        return portsByIndex.containsValue(node);
    }

    /** Read-only view of the port index → node map. Iteration order is registration order. */
    public Map<Integer, CircuitNode> getPorts() {
        return Collections.unmodifiableMap(portsByIndex);
    }

    /**
     * Read-only view of every internal node owned by this component (ports <em>and</em> any auxiliary
     * nodes such as a transistor's centre node). Used by placement / move handlers to stamp positions on
     * non-port nodes too, otherwise anchor-less internals stay parked at world origin.
     */
    public Set<CircuitNode> getNodes() {
        return Collections.unmodifiableSet(nodes);
    }

    /** Read-only view of internal edges owned by this component. */
    public Set<CircuitEdge> getEdges() {
        return Collections.unmodifiableSet(edges);
    }

    /**
     * Local offset from the component's visual centre to its pivot/anchor, in component-local
     * coordinates. The existing {@link LockInfo} pivot fields store this offset for components.
     * A component with no authored pivot offset behaves as if the offset were {@code (0, 0)}, so
     * its pivot/anchor coincides with its visual centre.
     */
    public double[] getPivotLocalOffset() {
        LockInfo lock = getInfo(AllElementInfos.LOCK);
        if (lock != null && lock.isPivotSet()) {
            return new double[] { lock.getPivotX(), lock.getPivotY() };
        }
        return new double[] { 0.0, 0.0 };
    }

    /** Returns the component's pivot/anchor world position. */
    public double[] getPivotWorldPosition() {
        PositionInfo pivot = getInfo(AllElementInfos.POSITION);
        return new double[] { pivot != null ? pivot.getX() : 0.0, pivot != null ? pivot.getY() : 0.0 };
    }

    /**
     * Returns the visual centre derived from the stored pivot/anchor position, rotation, and local
     * pivot offset. Rendering, anchor stamping, and physics body construction use this as the
     * rigid body's centre.
     */
    public double[] getVisualCenter() {
        double[] pivot = getPivotWorldPosition();
        double[] offset = getPivotLocalOffset();
        RotationInfo rot = getInfo(AllElementInfos.ROTATION);
        double angle = rot != null ? rot.getAngle() : 0.0;
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double ox = offset[0] * cos - offset[1] * sin;
        double oy = offset[0] * sin + offset[1] * cos;
        return new double[] { pivot[0] - ox, pivot[1] - oy };
    }

    /** Converts a world-space point to the component's local frame relative to its visual centre. */
    public double[] worldToLocalFromVisualCenter(double worldX, double worldY) {
        double[] centre = getVisualCenter();
        RotationInfo rot = getInfo(AllElementInfos.ROTATION);
        double angle = rot != null ? rot.getAngle() : 0.0;
        double cos = Math.cos(-angle);
        double sin = Math.sin(-angle);
        double dx = worldX - centre[0];
        double dy = worldY - centre[1];
        return new double[] { dx * cos - dy * sin, dx * sin + dy * cos };
    }

    /**
     * Default rotation pivot for a component with no authored pivot — the component centre when
     * known, otherwise an "empty" {@link LockState#FREE}. Always reports {@link LockMode#FREE};
     * the historical "soft-lock from internal port isFixed counts" derivation has been retired
     * now that the physics solver fully encodes the kinematic constraints of locked ports via
     * its body / constraint graph (a port pinned to other anchors contributes {@code invMass=0}
     * weight or distance / pin constraints, so the solver naturally refuses motion that the old
     * soft-lock would have flagged as LOCKED / PIVOTED).
     *
     * <p>Retained as a thin convenience for the UI panels and rotation-gesture defaults: they
     * still want a sensible "where should the rotation pivot live by default" answer, and the
     * component's own centre is the right choice when no {@link LockInfo#isPivotSet() authored
     * pivot} exists. Callers that need a real lock state should use
     * {@link #effectiveLockState(double)} (which now returns the strict {@link LockInfo} state
     * directly).
     */
    public LockState getSoftLockState() {
        PositionInfo centre = getInfo(AllElementInfos.POSITION);
        if (centre != null) {
            return new LockState(LockMode.FREE, centre.getX(), centre.getY(), true);
        }
        return LockState.FREE;
    }

    /**
     * Effective lock state = the strict {@link LockInfo} state. With the soft-lock derivation
     * retired (see {@link #getSoftLockState()} javadoc), strict is the sole authoritative source
     * for "what motion does the player permit on this element"; the kinematic constraints that
     * the old soft-lock used to encode (multiple anchored ports ⇒ LOCKED; one anchored port ⇒
     * PIVOTED) are now expressed directly to the physics solver as pin / distance
     * constraints on the body graph.
     *
     * <p>{@code epsilon} is retained on the signature for API stability with prior call sites,
     * but is unused — the strict {@link LockInfo}'s pivot is taken at face value with no
     * reconciliation needed.
     */
    public LockState effectiveLockState(double epsilon) {
        LockInfo strict = getInfo(AllElementInfos.LOCK);
        if (strict == null) {
            return LockState.FREE;
        }
        return switch (strict.getMode()) {
            case FREE -> LockState.FREE;
            case ORIENTED -> LockState.oriented();
            case PIVOTED -> {
                double[] pivot = getPivotWorldPosition();
                yield new LockState(LockMode.PIVOTED, pivot[0], pivot[1], true);
            }
            case LOCKED -> LockState.LOCKED;
        };
    }

    /**
     * Routes the external edge to the correct internal port node.
     */
    protected boolean connect(CircuitEdge edge, int index, boolean simulate){
        CircuitNode port = getPort(index);
        if (port == null) return false;

        return port.connectEdge(edge, simulate);
    }

    /**
     * Searches the internal nodes to find where this edge is attached, and disconnects it.
     */
    protected boolean disconnect(CircuitEdge edge, boolean simulate){
        for (CircuitNode node : nodes) {
            // Check if this node is actually holding the edge
            if (node.getConnection().contains(edge)) {
                return node.disconnect(edge, simulate);
            }
        }
        return false;
    }

    /**
     * Cleanly removes this component and every internal node/edge it owns, plus any external wires that
     * happen to be attached to one of its ports.
     *
     * <p>Destruction must be split into three regimes because {@link CircuitEdge#disconnect(boolean)} and
     * {@link CircuitEdge#connect} both refuse for edges with a non-null {@link #component} pointer (the
     * standard wire-management path is intentionally locked out from touching internals). Doing this in
     * one pass via {@link ServerCircuit#destroy(CircuitNode, boolean)} fails silently for internal edges
     * and leaves orphans behind.
     *
     * <ol>
     *     <li><b>External wires first:</b> any edge connected to a port whose {@code component == null}
     *     is a user-placed wire — drop it through the regular {@link ServerWorld#disconnect} path so the
     *     CircuitElementListener emits a REMOVE delta and any seperate-driven free-node rebind on the
     *     other side gets replicated correctly.</li>
     *     <li><b>Clear the internal lock:</b> null out {@code component} on every internal node and edge
     *     so the subsequent direct-removal path doesn't trip the hasComponent guards.</li>
     *     <li><b>Direct removal:</b> for each internal edge / node, post {@link ElementRemoveEvent}
     *     manually and yank it out of its actual circuit (which may differ from {@code masterCircuit}
     *     because {@code generate()} merges every internal into one shared circuit). We deliberately
     *     bypass {@link ServerWorld#disconnect}/{@link ServerWorld#destroy} here to skip their
     *     {@code seperate} pass — splitting Cmerged into per-leaf circuits during teardown would create
     *     a flurry of new circuits and ElementCircuitChangedEvents that the client mirror would have to
     *     unwind, which historically caused REBIND-source-circuit-not-found errors when the client's
     *     own destroy cascade reshuffled membership independently.</li>
     * </ol>
     *
     * <p>The component itself is removed from {@code masterCircuit.components()} after all internals are
     * gone, and any circuit that ends up empty as a side effect is dropped from the world so the client
     * gets a matching {@link com.minecart.event.events.CircuitLifecycleEvent.CircuitRemoveEvent}.
     */
    public void destroy(ServerCircuit masterCircuit) {
        World w = masterCircuit.getWorld();
        if (!(w instanceof ServerWorld serverWorld)) {
            return;
        }
        if (w.post(new ElementEvent.ElementRemoveEvent(w, this))) {
            return;
        }

        ArrayList<CircuitNode> nodesSnap = new ArrayList<>(nodes);
        ArrayList<CircuitEdge> edgesSnap = new ArrayList<>(edges);

        // 1) External wires attached to ports: standard disconnect path (REMOVE event + seperate-driven
        //    rebind for the free-node side, which is correct for that side).
        for (CircuitNode n : nodesSnap) {
            for (CircuitEdge ext : new ArrayList<>(n.getConnection())) {
                if (ext.hasComponent()) {
                    continue;
                }
                serverWorld.disconnect(ext);
            }
        }

        // 2) Clear our back-pointer on every internal so the manual-removal step below isn't blocked
        //    by hasComponent guards. Multi-owner aware: only drop OUR reference, leaving any shared
        //    ports as valid ports of whichever other component still claims them (Phase 2b cascade
        //    may leave a node in several ports' slots; destroying this component shouldn't strand
        //    those other relationships).
        for (CircuitNode n : nodesSnap) {
            n.removeComponent(this);
        }
        for (CircuitEdge e : edgesSnap) {
            e.setComponent(null);
        }

        // 3a) Internal edges: post REMOVE then directly take them out of their circuit and detach
        //     endpoints. Track which circuits we touched so we can prune empties at the end.
        java.util.LinkedHashSet<Circuit> touched = new java.util.LinkedHashSet<>();
        for (CircuitEdge e : edgesSnap) {
            w.post(new ElementEvent.ElementRemoveEvent(w, e));
            Circuit ec = e.getCircuit();
            if (ec != null) {
                ec.edges().remove(e);
                touched.add(ec);
            }
            CircuitNode start = e.getStart();
            CircuitNode end = e.getEnd();
            if (start != null) {
                start.disconnect(e, false);
            }
            if (end != null && end != start) {
                end.disconnect(e, false);
            }
            e.disconnect(false);
        }

        // 3b) Internal nodes: post REMOVE then take them out of their (possibly different) circuit.
        for (CircuitNode n : nodesSnap) {
            w.post(new ElementEvent.ElementRemoveEvent(w, n));
            Circuit nc = n.getCircuit();
            if (nc != null) {
                nc.nodes().remove(n);
                touched.add(nc);
            }
        }

        // 4) Remove the component from its master circuit.
        masterCircuit.components().remove(this);
        masterCircuit.markDirty();
        touched.add(masterCircuit);

        // 5) Drop any circuit that ended up completely empty (so a matching CircuitRemoveEvent reaches
        //    the client and the lifecycle pair stays balanced).
        for (Circuit cir : touched) {
            if (cir.nodes().isEmpty() && cir.edges().isEmpty() && cir.components().isEmpty()) {
                serverWorld.removeCircuit(cir);
            }
        }

        nodes.clear();
        edges.clear();
        portsByIndex.clear();
    }

    /**
     * Removes internal structural nodes without {@link ElementEvent} (topology replication / mirror apply).
     */
    public void destroyForTopologyMirror(Circuit circuit) {
        for (CircuitNode node : new ArrayList<>(nodes)) {
            circuit.destroyNodeForTopologyMirror(node);
        }
    }

    @Override
    public void save(CompoundTag tag) {
        super.save(tag);
        ListTag nodeIds = new ListTag();
        for (CircuitNode n : nodes) {
            nodeIds.add(TagUtil.writeUUID(n.getId()));
        }
        tag.put(CoreStrings.NODE_IDS, nodeIds);
        ListTag edgeIds = new ListTag();
        for (CircuitEdge e : edges) {
            edgeIds.add(TagUtil.writeUUID(e.getId()));
        }
        tag.put(CoreStrings.EDGE_IDS, edgeIds);
        // Port bindings: each entry is a compound {i: int, u: uuid}. Subclasses no longer need their
        // own per-port UUID save block; they can stop overriding {@link #getPort(int)} entirely once
        // every port is registered through the {@code (type, portIndex, ...)} newNode overloads.
        ListTag portList = new ListTag();
        for (Map.Entry<Integer, CircuitNode> e : portsByIndex.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt(CoreStrings.PORT_INDEX, e.getKey());
            TagUtil.putUUID(entry, CoreStrings.PORT_NODE_ID, e.getValue().getId());
            portList.add(entry);
        }
        tag.put(CoreStrings.PORT_BINDINGS, portList);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        Circuit c = getCircuit();
        if (c == null) {
            throw new IllegalStateException("CircuitComponent has no circuit");
        }
        // Internal nodes/edges live in whichever circuit they ended up merged into during server-side
        // generate(): each {@code newNode} starts in its own freshly-created circuit, then every
        // {@code newEdge} merges those circuits together. The component itself stays in its original
        // circuit. So a strict {@code c.findNode}/{@code c.findEdge} (only this component's circuit)
        // returns null for every internal id, leaving {@link #nodes} and {@link #edges} empty and the
        // {@code setComponent(this)} backlinks unset on the client. Search world-wide instead so the
        // mirror correctly matches the server's component<->internals topology.
        World w = c.getWorld();
        nodes.clear();
        edges.clear();
        Tag nodeIdsTag = tag.get(CoreStrings.NODE_IDS);
        if (nodeIdsTag instanceof ListTag nl) {
            for (int i = 0; i < nl.size(); i++) {
                UUID nu = TagUtil.readUUID(nl.get(i));
                if (nu == null) {
                    nu = TagUtil.parseUuidStringTag(nl.get(i));
                }
                if (nu == null) {
                    continue;
                }
                CircuitNode n = w != null ? w.findNode(nu) : c.findNode(nu);
                if (n != null) {
                    nodes.add(n);
                }
            }
        }
        Tag edgeIdsTag = tag.get(CoreStrings.EDGE_IDS);
        if (edgeIdsTag instanceof ListTag el) {
            for (int i = 0; i < el.size(); i++) {
                UUID eu = TagUtil.readUUID(el.get(i));
                if (eu == null) {
                    eu = TagUtil.parseUuidStringTag(el.get(i));
                }
                if (eu == null) {
                    continue;
                }
                CircuitEdge e = w != null ? w.findEdge(eu) : c.findEdge(eu);
                if (e != null) {
                    edges.add(e);
                }
            }
        }
        // Restore back-pointers via addComponent (multi-owner aware): if a Phase 2b cascade saved a
        // shared port, the same node appears in two components' NODE_IDS lists and each component's
        // load() should add ITSELF to the node's owner set without clearing the other. setComponent
        // would have collapsed the set to whichever component loaded last.
        for (CircuitNode n : nodes) {
            n.addComponent(this);
        }
        // Internal edges also need to know they belong to this component. Previously only nodes had
        // their {@code component} pointer restored here, leaving every loaded edge with a {@code null}
        // owner — and any client-side renderer using {@code edge.getComponent()} to hide internal wiring
        // (e.g. a transistor's centre-to-port struts) would happily draw them on top of the body
        // sprite. Mirrors the server-side {@code newEdge} path which already calls
        // {@code edge.setComponent(this)} at creation.
        for (CircuitEdge e : edges) {
            e.setComponent(this);
        }
        // Restore port index -> node bindings. Use the same world-scoped findNode as above so a port
        // that ended up in a different (merged) circuit than the component still resolves. Subclasses
        // that previously wrote their own per-name UUID save block can keep doing so for backward
        // compatibility, but new code should rely entirely on this map.
        portsByIndex.clear();
        Tag portsTag = tag.get(CoreStrings.PORT_BINDINGS);
        if (portsTag instanceof ListTag pl) {
            for (int i = 0; i < pl.size(); i++) {
                if (!(pl.get(i) instanceof CompoundTag entry)) {
                    continue;
                }
                int idx = entry.getInt(CoreStrings.PORT_INDEX);
                UUID uu = TagUtil.getUUID(entry, CoreStrings.PORT_NODE_ID);
                if (uu == null) {
                    continue;
                }
                CircuitNode portNode = w != null ? w.findNode(uu) : c.findNode(uu);
                if (portNode != null) {
                    portsByIndex.put(idx, portNode);
                }
            }
        }
    }

    /** Snapshot keys for the component's pivot/anchor position, rotation, and strict lock. */
    public static final String FIELD_POSITION_X = "core:position.x";
    public static final String FIELD_POSITION_Y = "core:position.y";
    public static final String FIELD_ROTATION = "core:rotation.angle";
    public static final String FIELD_LOCK_MODE = "core:lock.mode";
    public static final String FIELD_LOCK_PIVOT_X = "core:lock.pivotX";
    public static final String FIELD_LOCK_PIVOT_Y = "core:lock.pivotY";

    static {
        InfoPanelRegistry.registerComponentFragment((component, builder) -> {
            PositionInfo pivot = component.getInfo(AllElementInfos.POSITION);
            LockInfo lock = component.getInfo(AllElementInfos.LOCK);
            LockMode current = lock != null ? lock.getMode() : LockMode.FREE;
            boolean pivoted = current == LockMode.PIVOTED;
            if (pivot != null) {
                if (pivoted) {
                    builder.add(new LabelSpec(FIELD_POSITION_X + ".readonly", "Pivot X", Double.toString(pivot.getX())));
                    builder.add(new LabelSpec(FIELD_POSITION_Y + ".readonly", "Pivot Y", Double.toString(pivot.getY())));
                } else {
                    builder.add(new NumberFieldSpec(FIELD_POSITION_X, "Pivot X", pivot.getX()));
                    builder.add(new NumberFieldSpec(FIELD_POSITION_Y, "Pivot Y", pivot.getY()));
                }
            }
            RotationInfo rotation = component.getInfo(AllElementInfos.ROTATION);
            if (rotation != null) {
                builder.add(new NumberFieldSpec(FIELD_ROTATION, "Rotation (rad)", rotation.getAngle()));
            }
            boolean lockEditable = lock == null || lock.isMutableByPlayer();
            if (lockEditable) {
                java.util.List<String> options = java.util.List.of(
                        LockMode.FREE.name(),
                        LockMode.ORIENTED.name(),
                        LockMode.PIVOTED.name(),
                        LockMode.LOCKED.name());
                builder.add(new DropdownSpec(FIELD_LOCK_MODE, "Lock", options, current.name()));
                double[] offset = component.getPivotLocalOffset();
                if (pivoted) {
                    builder.add(new LabelSpec(FIELD_LOCK_PIVOT_X + ".readonly", "Pivot Offset X",
                            Double.toString(offset[0])));
                    builder.add(new LabelSpec(FIELD_LOCK_PIVOT_Y + ".readonly", "Pivot Offset Y",
                            Double.toString(offset[1])));
                } else {
                    builder.add(new NumberFieldSpec(FIELD_LOCK_PIVOT_X, "Pivot Offset X", offset[0]));
                    builder.add(new NumberFieldSpec(FIELD_LOCK_PIVOT_Y, "Pivot Offset Y", offset[1]));
                }
            }
        });

        InfoPanelRegistry.registerComponentFragmentSaveHandler((component, snapshot, evt) -> {
            boolean mutated = false;

            // Lock mode + pivot first so subsequent translate / rotate edits see the new state and
            // refuse if the user just locked the component.
            String modeName = snapshot.getString(FIELD_LOCK_MODE).orElse(null);
            Double pivotX = snapshot.getDouble(FIELD_LOCK_PIVOT_X).orElse(null);
            Double pivotY = snapshot.getDouble(FIELD_LOCK_PIVOT_Y).orElse(null);
            if (modeName != null) {
                LockMode parsed;
                try { parsed = LockMode.valueOf(modeName); }
                catch (IllegalArgumentException e) { parsed = null; }
                if (parsed != null) {
                    LockInfo lock = component.getInfo(AllElementInfos.LOCK);
                    if (lock == null && parsed != LockMode.FREE) {
                        lock = new LockInfo();
                        component.setInfo(AllElementInfos.LOCK, lock);
                        mutated = true;
                    }
                    if (lock != null && lock.isMutableByPlayer()) {
                        boolean wasPivoted = lock.getMode() == LockMode.PIVOTED;
                        if (lock.setMode(parsed)) mutated = true;
                        if (!wasPivoted
                                && pivotX != null && pivotY != null
                                && Double.isFinite(pivotX) && Double.isFinite(pivotY)
                                && (!lock.isPivotSet() || pivotX != lock.getPivotX() || pivotY != lock.getPivotY())) {
                            double[] visualBefore = component.getVisualCenter();
                            lock.setPivot(pivotX, pivotY);
                            PositionInfo pivotPos = component.getInfo(AllElementInfos.POSITION);
                            RotationInfo rot = component.getInfo(AllElementInfos.ROTATION);
                            double angle = rot != null ? rot.getAngle() : 0.0;
                            double cos = Math.cos(angle);
                            double sin = Math.sin(angle);
                            double worldOffsetX = pivotX * cos - pivotY * sin;
                            double worldOffsetY = pivotX * sin + pivotY * cos;
                            if (pivotPos != null) {
                                pivotPos.set(visualBefore[0] + worldOffsetX,
                                        visualBefore[1] + worldOffsetY);
                            }
                            mutated = true;
                        }
                    }
                }
            }

            // Translate + rotate go through the cascade engine — same atomicity / preflight /
            // rollback story as cross-component combines. The engine internally consults
            // effectiveLockState and refuses motion that the effective lock doesn't allow, so the
            // strict-lock-just-applied → translate-refused interaction works the same as before.
            ServerWorld sw = component.getWorld() instanceof ServerWorld w ? w : null;

            // Component pivot/anchor position: convert "new pivot" to a translation delta.
            PositionInfo pivot = component.getInfo(AllElementInfos.POSITION);
            Double newX = snapshot.getDouble(FIELD_POSITION_X).orElse(null);
            Double newY = snapshot.getDouble(FIELD_POSITION_Y).orElse(null);
            boolean pivotedNow = component.effectiveLockState(1e-6).mode() == LockMode.PIVOTED;
            if (!pivotedNow && sw != null && pivot != null && newX != null && newY != null
                    && Double.isFinite(newX) && Double.isFinite(newY)) {
                double dx = newX - pivot.getX();
                double dy = newY - pivot.getY();
                if (dx != 0.0 || dy != 0.0) {
                    if (CombineCascadeEngine.tryTranslateComponent(sw, component, dx, dy)) {
                        mutated = true;
                    }
                }
            }

            // Component rotation: convert "new angle" to a delta, rotate around the component's
            // current pivot/anchor. FREE components have a zero pivot offset by default, so this
            // is equivalent to centre rotation until the user authors a pivot offset.
            RotationInfo rotation = component.getInfo(AllElementInfos.ROTATION);
            Double newAngle = snapshot.getDouble(FIELD_ROTATION).orElse(null);
            if (sw != null && rotation != null && pivot != null
                    && newAngle != null && Double.isFinite(newAngle)) {
                double delta = newAngle - rotation.getAngle();
                if (delta != 0.0) {
                    if (CombineCascadeEngine.tryRotateComponent(sw, component, pivot.getX(),
                            pivot.getY(), delta)) {
                        mutated = true;
                    }
                }
            }

            if (mutated && component.getWorld() != null) {
                component.getWorld().getLevel().notifyElementChanged(component);
            }
        });
    }
}
