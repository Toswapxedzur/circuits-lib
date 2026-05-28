package com.minecart.logic;

import com.minecart.misc.CoreStrings;
import com.google.common.graph.EndpointPair;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.math.DoubleVar;
import com.minecart.misc.CurrentFlow;
import com.minecart.logic.cascade.CombineCascadeEngine;
import com.minecart.registry.AllElementInfos;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.ui.panel.InfoPanelRegistry;
import com.minecart.ui.panel.fields.CheckboxSpec;
import com.minecart.ui.panel.fields.DropdownSpec;
import com.minecart.ui.panel.fields.NumberFieldSpec;
import com.minecart.variant.info.LockInfo;
import com.minecart.variant.info.LockMode;
import com.minecart.variant.info.LockState;
import com.minecart.variant.info.PositionInfo;

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

    /**
     * Soft lock state derived from this edge's two endpoint nodes. Walks {@link #start} and
     * {@link #end} (skipping nulls) and counts how many carry {@link PositionInfo#isFixed()}.
     *
     * <p>Per the design (Q2 in the lock-state design pass): a port endpoint's {@code isFixed}
     * does NOT contribute here — port locks are an anchor-bookkeeping detail of the parent
     * component, not a constraint on the edge itself. Component-attached endpoints behave as if
     * they were free for the purpose of edge soft lock; the parent component's own lock state
     * still gates any cascade that would have to translate it. To detect "this endpoint is a
     * port" we ask whether the node has any owning component; free nodes participate as usual.
     *
     * <ul>
     *   <li>0 free endpoints locked → {@link LockMode#FREE}; pivot defaults to midpoint of the
     *       two endpoints (the rotation gesture's natural pivot for an unconstrained line).</li>
     *   <li>1 free endpoint locked → {@link LockMode#ROTATION_FREE} pivoted at that endpoint.</li>
     *   <li>2 free endpoints locked → {@link LockMode#LOCKED}.</li>
     * </ul>
     */
    public LockState getSoftLockState() {
        int lockedCount = 0;
        double lockedX = 0.0;
        double lockedY = 0.0;
        CircuitNode[] endpoints = new CircuitNode[]{start, end};
        for (CircuitNode n : endpoints) {
            if (n == null) {
                continue;
            }
            if (n.hasComponent()) {
                // Port-anchored: doesn't bind this edge — see method javadoc.
                continue;
            }
            PositionInfo p = n.getInfo(AllElementInfos.POSITION);
            if (p != null && p.isFixed()) {
                lockedCount++;
                if (lockedCount == 1) {
                    lockedX = p.getX();
                    lockedY = p.getY();
                }
                if (lockedCount > 1) {
                    return LockState.LOCKED;
                }
            }
        }
        if (lockedCount == 0) {
            // FREE with midpoint as the default pivot. Midpoint is undefined when an endpoint is
            // missing or has no PositionInfo; fall back to (0,0) with pivotValid=false in that case
            // so the gesture code can pick a sensible default itself.
            PositionInfo ps = start != null ? start.getInfo(AllElementInfos.POSITION) : null;
            PositionInfo pe = end != null ? end.getInfo(AllElementInfos.POSITION) : null;
            if (ps != null && pe != null) {
                return new LockState(LockMode.FREE,
                        (ps.getX() + pe.getX()) * 0.5,
                        (ps.getY() + pe.getY()) * 0.5,
                        true);
            }
            return LockState.FREE;
        }
        return LockState.rotationFree(lockedX, lockedY);
    }

    /**
     * Effective lock state for this edge = {@link LockState#and AND} of strict ({@link LockInfo})
     * and soft. See {@link CircuitComponent#effectiveLockState(double)} for the strict-side
     * lookup convention.
     */
    public LockState effectiveLockState(double epsilon) {
        LockState soft = getSoftLockState();
        LockInfo strict = getInfo(AllElementInfos.LOCK);
        LockState strictState;
        if (strict == null) {
            strictState = LockState.FREE;
        } else if (strict.getMode() == LockMode.ROTATION_FREE && strict.isPivotSet()) {
            strictState = LockState.rotationFree(strict.getPivotX(), strict.getPivotY());
        } else if (strict.getMode() == LockMode.ROTATION_FREE) {
            strictState = LockState.FREE;
        } else {
            strictState = switch (strict.getMode()) {
                case FREE -> LockState.FREE;
                case POSITION_FREE -> LockState.positionFree();
                case LOCKED -> LockState.LOCKED;
                default -> LockState.FREE;
            };
        }
        return LockState.and(strictState, soft, epsilon);
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

    /** Snapshot keys for the edge's endpoint positions and strict lock. */
    public static final String FIELD_START_X = "core:edge.startX";
    public static final String FIELD_START_Y = "core:edge.startY";
    public static final String FIELD_END_X = "core:edge.endX";
    public static final String FIELD_END_Y = "core:edge.endY";
    public static final String FIELD_LOCK_MODE = "core:lock.mode";
    public static final String FIELD_LOCK_PIVOT_X = "core:lock.pivotX";
    public static final String FIELD_LOCK_PIVOT_Y = "core:lock.pivotY";

    static {
        // Cross-cutting fragment for every CircuitEdge: editable endpoint coordinates + the 4-mode
        // strict lock. Endpoints that are port nodes (hasComponent) are shown but Phase 3a refuses
        // their edits — translating one of them implies translating the parent component, which
        // wants the cascade engine (Phase 3b). Free endpoints apply directly.
        InfoPanelRegistry.registerEdgeFragment((edge, builder) -> {
            // Start endpoint coords.
            CircuitNode s = edge.getStart();
            if (s != null) {
                PositionInfo sp = s.getInfo(AllElementInfos.POSITION);
                if (sp != null && !(sp.isFixed() && !sp.canChangeFix())) {
                    builder.add(new NumberFieldSpec(FIELD_START_X, "Start X", sp.getX()));
                    builder.add(new NumberFieldSpec(FIELD_START_Y, "Start Y", sp.getY()));
                }
            }
            CircuitNode e = edge.getEnd();
            if (e != null) {
                PositionInfo ep = e.getInfo(AllElementInfos.POSITION);
                if (ep != null && !(ep.isFixed() && !ep.canChangeFix())) {
                    builder.add(new NumberFieldSpec(FIELD_END_X, "End X", ep.getX()));
                    builder.add(new NumberFieldSpec(FIELD_END_Y, "End Y", ep.getY()));
                }
            }
            // Lock dropdown + pivot. Hidden when mutableByPlayer=false on an existing LockInfo
            // (e.g. structurally-immutable edges); we always SHOW the row for plain edges even
            // when no LockInfo exists yet (defaulting to FREE) so the player can author one.
            LockInfo lock = edge.getInfo(AllElementInfos.LOCK);
            boolean lockEditable = lock == null || lock.isMutableByPlayer();
            if (lockEditable) {
                LockMode current = lock != null ? lock.getMode() : LockMode.FREE;
                java.util.List<String> options = java.util.List.of(
                        LockMode.FREE.name(),
                        LockMode.POSITION_FREE.name(),
                        LockMode.ROTATION_FREE.name(),
                        LockMode.LOCKED.name());
                builder.add(new DropdownSpec(FIELD_LOCK_MODE, "Lock", options, current.name()));
                // Pivot fields: only meaningful in ROTATION_FREE but always present so flipping
                // mode in the panel without re-opening doesn't lose the pivot. Initial seeded
                // from the LockInfo if set, else from the soft pivot (midpoint of endpoints).
                double pivotX = 0.0, pivotY = 0.0;
                if (lock != null && lock.isPivotSet()) {
                    pivotX = lock.getPivotX();
                    pivotY = lock.getPivotY();
                } else {
                    LockState soft = edge.getSoftLockState();
                    if (soft.pivotValid()) {
                        pivotX = soft.pivotX();
                        pivotY = soft.pivotY();
                    }
                }
                builder.add(new NumberFieldSpec(FIELD_LOCK_PIVOT_X, "Pivot X", pivotX));
                builder.add(new NumberFieldSpec(FIELD_LOCK_PIVOT_Y, "Pivot Y", pivotY));
            }
        });

        // Save handler. Applies lock first, then endpoint coords. Port endpoints refuse here
        // (TODO phase-3b: route through cascade); free endpoints update the node's PositionInfo
        // in place and notify.
        InfoPanelRegistry.registerEdgeFragmentSaveHandler((edge, snapshot, evt) -> {
            boolean mutated = false;

            // Lock mode + pivot. Lazily create LockInfo if the player flips to anything other than
            // FREE and we don't have one yet — this is the panel-authoring path the user spec
            // explicitly allows ("registered from within CircuitEdge").
            String modeName = snapshot.getString(FIELD_LOCK_MODE).orElse(null);
            Double pivotX = snapshot.getDouble(FIELD_LOCK_PIVOT_X).orElse(null);
            Double pivotY = snapshot.getDouble(FIELD_LOCK_PIVOT_Y).orElse(null);
            if (modeName != null) {
                LockMode parsed;
                try {
                    parsed = LockMode.valueOf(modeName);
                } catch (IllegalArgumentException badMode) {
                    parsed = null;
                }
                if (parsed != null) {
                    LockInfo lock = edge.getInfo(AllElementInfos.LOCK);
                    if (lock == null && parsed != LockMode.FREE) {
                        lock = new LockInfo();
                        edge.setInfo(AllElementInfos.LOCK, lock);
                        mutated = true;
                    }
                    if (lock != null && lock.isMutableByPlayer()) {
                        if (lock.setMode(parsed)) {
                            mutated = true;
                        }
                        if (pivotX != null && pivotY != null
                                && Double.isFinite(pivotX) && Double.isFinite(pivotY)
                                && (pivotX != lock.getPivotX() || pivotY != lock.getPivotY() || !lock.isPivotSet())) {
                            lock.setPivot(pivotX, pivotY);
                            mutated = true;
                        }
                    }
                }
            }

            // Endpoint coords. Per-endpoint: only apply when the node is free (no component
            // owners). Port endpoints need the cascade — drop silently.
            mutated |= applyEdgeEndpoint(edge.getStart(), snapshot, FIELD_START_X, FIELD_START_Y);
            mutated |= applyEdgeEndpoint(edge.getEnd(), snapshot, FIELD_END_X, FIELD_END_Y);

            if (mutated && edge.getWorld() != null) {
                edge.getWorld().getLevel().notifyElementChanged(edge);
            }
        });
    }

    private static boolean applyEdgeEndpoint(CircuitNode node, com.minecart.ui.panel.PanelSnapshot snap,
                                             String keyX, String keyY) {
        if (node == null) return false;
        Double newX = snap.getDouble(keyX).orElse(null);
        Double newY = snap.getDouble(keyY).orElse(null);
        if (newX == null || newY == null) return false;
        if (!Double.isFinite(newX) || !Double.isFinite(newY)) return false;

        // Port endpoint → route through the cascade engine (translate parent component to bring
        // the port to the new position). Failure is silent: sync will re-assert.
        if (!node.getComponents().isEmpty()) {
            if (node.getWorld() instanceof ServerWorld sw) {
                CombineCascadeEngine.tryMovePortNode(sw, node, newX, newY);
            }
            return false; // engine handles its own notify; we report no direct mutation
        }

        PositionInfo p = node.getInfo(AllElementInfos.POSITION);
        if (p == null || (p.isFixed() && !p.canChangeFix())) return false;
        if (p.isFixed()) return false; // soft lock honoured: locked nodes don't move via panel
        if (newX == p.getX() && newY == p.getY()) return false;
        p.set(newX, newY);
        if (node.getWorld() != null) {
            node.getWorld().getLevel().notifyElementChanged(node);
        }
        return true;
    }
}
