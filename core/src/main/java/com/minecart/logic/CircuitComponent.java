package com.minecart.logic;

import com.minecart.math.LinearSystem;
import com.minecart.misc.CoreStrings;
import com.minecart.variant.ElectricalVariate;
import com.minecart.variant.ElectricalInfo;
import com.minecart.event.events.ElementEvent;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.registry.CircuitElementType;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.ListTag;
import com.minecart.serialization.tag.Tag;

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
        oldPort.setComponent(null);
        newPort.setComponent(this);
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

        // 2) Clear backlinks so the manual-removal step below isn't blocked by hasComponent guards.
        for (CircuitNode n : nodesSnap) {
            n.setComponent(null);
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
        for (CircuitNode n : nodes) {
            n.setComponent(this);
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
}
