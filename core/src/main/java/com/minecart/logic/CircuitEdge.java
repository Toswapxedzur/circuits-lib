package com.minecart.logic;

import com.minecart.misc.CoreStrings;
import com.google.common.graph.EndpointPair;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.math.DoubleVar;
import com.minecart.misc.CurrentFlow;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.Set;
import java.util.UUID;

public non-sealed class CircuitEdge extends CircuitElement {
    public static final double MAX_CURRENT = 1e15;

    //positive: from first to second
    protected DoubleVar current;

    protected CircuitNode start;
    protected CircuitNode end;

    protected CircuitComponent component;

    protected boolean overpowered;

    public CircuitEdge(World world){
        setWorld(world);
        current = DoubleVar.create();
        overpowered = false;
    }

    @Override
    public void collectVariable(Set<DoubleVar> variables) {
        super.collectVariable(variables);
        variables.add(current);
    }

    @Override
    public void tick(){
        overpowered = shortCircuit();
    }

    /**
     * Whether this edge was last evaluated as overpowered ({@link #shortCircuit()}) after the previous tick.
     * Used with {@link #shortCircuit()} before {@link #tick()} to detect a transition into overpowered.
     */
    public boolean isOverpowered() {
        return overpowered;
    }

    public boolean shortCircuit(){
        return Math.abs(current.getValue()) > MAX_CURRENT;
    }

    public boolean connect(CircuitNode fromConnect, CircuitNode toConnect, boolean simulate){
        if(hasComponent())
            return false;
        if(!simulate) {
            start = fromConnect;
            end = toConnect;
        }
        return true;
    }

    public boolean disconnect(boolean simulate){
        if(hasComponent())
            return false;
        if(!simulate) {
            start = null;
            end = null;
        }
        return true;
    }

    public CircuitNode getStart() {
        return start;
    }

    public CircuitNode getEnd() {
        return end;
    }

    public CircuitComponent getComponent() {
        return component;
    }

    public boolean hasComponent() {
        return component != null;
    }

    public void setComponent(CircuitComponent component) {
        this.component = component;
    }

    public boolean isConnected(){
        return start != null && end != null;
    }

    public CircuitNode getConnection(int index) {
        return index == 0 ? start : end;
    }

    public int getIndex(CircuitNode node){
        return getConnection(0) == node ? 0 : 1;
    }

    public CircuitNode getOther(CircuitNode node) {
        return getIndex(node) == 0 ? getConnection(1) : getConnection(0);
    }

    public boolean connectTo(CircuitNode node){
        return getConnection(0) == node || getConnection(1) == node;
    }

    public DoubleVar getCurrent() {
        return current;
    }

    public boolean shouldRevert(CircuitNode node){
        return node.equals(getConnection(1));
    }

    public CurrentFlow flowDirection(CircuitNode node){
        if(current.getValue() == 0f)
            return CurrentFlow.NO;
        if(getConnection(sourceInx()) == node)
            return CurrentFlow.OUT;
        return CurrentFlow.IN;
    }

    protected int sourceInx(){
        return current.getValue() < 0 ? 1 : 0;
    }

    protected int targetInx(){
        return current.getValue() < 0 ? 0 : 1;
    }

    public CircuitNode getSource(){
        return getConnection(sourceInx());
    }

    public CircuitNode getTarget(){
        return getConnection(targetInx());
    }

    public EndpointPair<CircuitNode> incidentNodes(){
        return EndpointPair.ordered(getSource(), getTarget());
    }

    @Override
    public void save(CompoundTag tag) {
        super.save(tag);
        if (getStart() != null) {
            TagUtil.putUUID(tag, CoreStrings.EDGE_START, getStart().getId());
        }
        if (getEnd() != null) {
            TagUtil.putUUID(tag, CoreStrings.EDGE_END, getEnd().getId());
        }
        tag.putDouble(CoreStrings.EDGE_CURRENT, getCurrent().getValue());
        tag.putBoolean(CoreStrings.EDGE_OVERPOWERED, overpowered);
    }

    /**
     * Restores id and electrical state from {@code tag}. Two modes:
     *
     * <ol>
     *     <li><b>Initial load</b> (called from {@link Circuit#load} via {@link #load(CompoundTag, Circuit)})
     *         — at this point {@link #start} and {@link #end} are still {@code null}, so the reattach branch
     *         below short-circuits and {@link #attachEndpointsFromTag} (called separately by the
     *         {@code (tag, circuit)} overload) does the actual wiring.</li>
     *     <li><b>Sync reattach</b> (called from
     *         {@link com.minecart.protocol.sync.SyncRegistry#readSyncData} when a server CHANGE op carries
     *         updated endpoint UUIDs) — endpoints are already wired but the {@link CoreStrings#EDGE_START} /
     *         {@link CoreStrings#EDGE_END} ids in {@code tag} differ. Detach from the old endpoints (bypassing
     *         the {@code hasComponent} guard since the server has already authorised this rebind) and attach
     *         to the new ones via the world's cross-circuit {@link com.minecart.foundation.World#findNode
     *         findNode}, which handles the case where the new endpoint lives in a different circuit than the
     *         edge.</li>
     * </ol>
     *
     * <p>The single-method dual mode keeps the {@link com.minecart.protocol.sync.SyncRegistry} default path
     * (which dispatches to {@link CircuitElement#save}/{@link CircuitElement#load}) working for every edge
     * subclass without needing per-class custom handlers.
     */
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        getCurrent().setValue(tag.getDouble(CoreStrings.EDGE_CURRENT));
        overpowered = tag.getBoolean(CoreStrings.EDGE_OVERPOWERED);
        if (start == null && end == null) {
            return;
        }
        UUID startId = TagUtil.getUUID(tag, CoreStrings.EDGE_START);
        UUID endId = TagUtil.getUUID(tag, CoreStrings.EDGE_END);
        if (startId == null || endId == null) {
            return;
        }
        UUID currStartId = start != null ? start.getId() : null;
        UUID currEndId = end != null ? end.getId() : null;
        if (startId.equals(currStartId) && endId.equals(currEndId)) {
            return;
        }
        World w = getWorld();
        if (w == null) {
            return;
        }
        CircuitNode newStart = w.findNode(startId);
        CircuitNode newEnd = w.findNode(endId);
        if (newStart == null || newEnd == null) {
            return;
        }
        replaceEndpointsBypassingGuards(newStart, newEnd);
    }

    /**
     * Direct {@code start}/{@code end} swap that bypasses the {@code hasComponent} guard on
     * {@link #connect}/{@link #disconnect}. Used by:
     *
     * <ul>
     *     <li>The sync-reattach branch in {@link #load(CompoundTag)} when the client mirror applies a
     *         server-issued endpoint change.</li>
     *     <li>{@link com.minecart.logic.ServerWorld#changeEdgeEndpoint} when the editor / a test moves an
     *         edge from one node to another (e.g. as part of {@code combineNodes}).</li>
     * </ul>
     *
     * <p>The guard exists to prevent user-driven wiring tools from severing a component's internal star
     * graph; authoritative replication and explicit endpoint-mutation API calls have already validated the
     * transition, so they're permitted to push past it. Connection-set membership on the involved nodes is
     * updated here too so {@link CircuitNode#getConnection()} stays consistent in both directions.
     *
     * <p>{@code newStart} / {@code newEnd} must be non-null. To clear endpoints during a teardown use
     * {@link #disconnect(boolean)} instead.
     */
    void replaceEndpointsBypassingGuards(CircuitNode newStart, CircuitNode newEnd) {
        if (newStart == null || newEnd == null) {
            throw new IllegalArgumentException("newStart and newEnd must be non-null");
        }
        CircuitNode oldStart = this.start;
        CircuitNode oldEnd = this.end;
        if (oldStart != null && oldStart != newStart && oldStart != newEnd) {
            oldStart.disconnect(this, false);
        }
        if (oldEnd != null && oldEnd != oldStart && oldEnd != newStart && oldEnd != newEnd) {
            oldEnd.disconnect(this, false);
        }
        this.start = newStart;
        this.end = newEnd;
        newStart.connectEdge(this, false);
        if (newEnd != newStart) {
            newEnd.connectEdge(this, false);
        }
    }

    /**
     * Loads electrical state from {@code tag}, then resolves {@code start}/{@code end} against {@code circuit}
     * and wires this edge to those nodes.
     */
    public void load(CompoundTag tag, Circuit circuit) {
        load(tag);
        attachEndpointsFromTag(tag, circuit);
    }

    private void attachEndpointsFromTag(CompoundTag tag, Circuit circuit) {
        UUID startId = TagUtil.getUUID(tag, CoreStrings.EDGE_START);
        UUID endId = TagUtil.getUUID(tag, CoreStrings.EDGE_END);
        if (startId == null || endId == null) {
            throw new IllegalArgumentException("Edge missing start/end: " + getId());
        }
        CircuitNode n1 = circuit.findNode(startId);
        CircuitNode n2 = circuit.findNode(endId);
        if (n1 == null || n2 == null) {
            throw new IllegalArgumentException("Missing endpoint node for edge " + getId());
        }
        if (!connect(n1, n2, true)) {
            throw new IllegalArgumentException("Edge cannot connect: " + getId());
        }
        connect(n1, n2, false);
        if (!n1.connectEdge(this, true) || !n2.connectEdge(this, true)) {
            throw new IllegalArgumentException("Edge cannot attach to nodes: " + getId());
        }
        n1.connectEdge(this, false);
        n2.connectEdge(this, false);
    }
}
