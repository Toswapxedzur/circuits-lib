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
import com.minecart.variant.info.RigidityInfo;

import java.util.Set;
import java.util.UUID;

public non-sealed class CircuitEdge extends CircuitElement {
    public static final double MAX_CURRENT = 1e15;

    /**
     * Threshold below which a solver-produced current is treated as "no flow". The current is a
     * {@code double} produced by the linear solve, so an exact {@code == 0} test essentially never
     * fires; use a small epsilon so numerically-negligible currents report {@link CurrentFlow#NO}.
     */
    private static final double NO_FLOW_EPSILON = 1e-12;

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
     * Default rotation pivot for this edge — midpoint of its two endpoints when both endpoint
     * positions are known, otherwise an "empty" {@link LockState#FREE}. Always reports
     * {@link LockMode#FREE}; the historical isFixed-counting derivation has been retired now
     * that the physics solver encodes endpoint anchoring as pin / distance constraints on the
     * body graph (see {@link CircuitComponent#getSoftLockState()} for the full rationale).
     *
     * <p>Retained as a panel-default-pivot helper: the edge panel uses this when no
     * {@link LockInfo#isPivotSet() authored pivot} is present, and a midpoint is a sensible
     * default that doesn't depend on lock semantics at all.
     */
    public LockState getSoftLockState() {
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

    /**
     * Effective lock state for this edge = the strict {@link LockInfo} state. See
     * {@link CircuitComponent#effectiveLockState(double)} for the contract; the soft-lock half
     * is no longer combined in (kinematic anchoring is now expressed to the physics solver as
     * constraints on the body graph rather than as a derived lock mode here).
     */
    public LockState effectiveLockState(double epsilon) {
        LockInfo strict = getInfo(AllElementInfos.LOCK);
        if (strict == null) {
            return LockState.FREE;
        }
        return switch (strict.getMode()) {
            case FREE -> LockState.FREE;
            case ORIENTED -> LockState.oriented();
            case PIVOTED -> strict.isPivotSet()
                    ? LockState.pivoted(strict.getPivotX(), strict.getPivotY())
                    : LockState.FREE;
            case LOCKED -> LockState.LOCKED;
        };
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
        if(Math.abs(current.getValue()) <= NO_FLOW_EPSILON)
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
        // Preflight capacity BEFORE detaching the old endpoints. A capacity-limited node
        // (e.g. Junction.connectEdge returns false when full) would otherwise leave
        // edge.start == newStart while newStart.getConnection() lacks this edge — an asymmetric
        // graph that yields wrong solves. Simulate both connects first and refuse the whole swap
        // if either would be rejected, so the old adjacency is left untouched on failure.
        if (!newStart.connectEdge(this, true)) {
            throw new IllegalStateException(
                    "newStart node rejected edge (at capacity): " + newStart.getId());
        }
        if (newEnd != newStart && !newEnd.connectEdge(this, true)) {
            throw new IllegalStateException(
                    "newEnd node rejected edge (at capacity): " + newEnd.getId());
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
        // The simulate checks above already confirmed both connects succeed; assert on the real
        // call so a capacity race can never silently corrupt adjacency.
        if (!newStart.connectEdge(this, false)) {
            throw new IllegalStateException(
                    "newStart node rejected edge (at capacity): " + newStart.getId());
        }
        if (newEnd != newStart && !newEnd.connectEdge(this, false)) {
            throw new IllegalStateException(
                    "newEnd node rejected edge (at capacity): " + newEnd.getId());
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
        World w = circuit.getWorld();
        CircuitNode n1 = w != null ? w.findNode(startId) : circuit.findNode(startId);
        CircuitNode n2 = w != null ? w.findNode(endId) : circuit.findNode(endId);
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
    /**
     * Rigid / flexible toggle. When {@code true}, this edge contributes a fixed-length distance
     * constraint to the physics solver and dragging either endpoint pulls the other along; when
     * {@code false} (default) the edge is purely visual. Authored via the edge panel; persisted
     * through {@link RigidityInfo}.
     */
    public static final String FIELD_RIGID = "core:edge.rigid";

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
                        LockMode.ORIENTED.name(),
                        LockMode.PIVOTED.name(),
                        LockMode.LOCKED.name());
                builder.add(new DropdownSpec(FIELD_LOCK_MODE, "Lock", options, current.name()));
                // Pivot fields: only meaningful in PIVOTED but always present so flipping
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
            // Rigid / flexible toggle. Always present, defaulting to whatever RigidityInfo carries
            // (which the info-injector ensures is always non-null on a freshly placed edge).
            RigidityInfo rigid = edge.getInfo(AllElementInfos.RIGIDITY);
            boolean rigidCurrent = rigid != null && rigid.isRigid();
            builder.add(new CheckboxSpec(FIELD_RIGID, "Rigid", rigidCurrent));
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

            // Endpoint coords. Edge locks gate these as a single edge edit:
            // FREE allows independent endpoint movement; ORIENTED allows only equal endpoint
            // deltas (pure translation); PIVOTED/LOCKED refuse pivot/position edits.
            mutated |= applyLockedEdgeEndpointEdits(edge, snapshot);

            // Rigid toggle: apply last so the next solver pass (driven by any subsequent gesture)
            // sees the freshly-set flag. We lazily create RigidityInfo if absent — the injector
            // normally attaches it at element insert time, but defensive creation here makes the
            // panel save robust to elements that predate the injector wiring.
            Boolean wantsRigid = snapshot.getBoolean(FIELD_RIGID).orElse(null);
            if (wantsRigid != null) {
                RigidityInfo rigid = edge.getInfo(AllElementInfos.RIGIDITY);
                if (rigid == null) {
                    rigid = new RigidityInfo();
                    edge.setInfo(AllElementInfos.RIGIDITY, rigid);
                    mutated = true;
                }
                if (rigid.setRigid(wantsRigid)) {
                    mutated = true;
                }
            }

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
        if (p.isFixed()) return false; // node-level lock honoured: fixed free nodes don't move via panel
        if (newX == p.getX() && newY == p.getY()) return false;
        // Route through the cascade engine so any rigid edges incident to the free endpoint
        // propagate the panel-driven move. The engine notifies each touched element on success.
        if (node.getWorld() instanceof ServerWorld sw) {
            CombineCascadeEngine.tryTranslateFreeNode(sw, node,
                    newX - p.getX(), newY - p.getY());
        }
        return true;
    }

    private static boolean applyLockedEdgeEndpointEdits(CircuitEdge edge,
                                                        com.minecart.ui.panel.PanelSnapshot snap) {
        if (edge == null) return false;
        LockState eff = edge.effectiveLockState(1e-6);
        LockMode mode = eff.mode();
        if (mode == LockMode.LOCKED || mode == LockMode.PIVOTED) {
            return false;
        }
        if (mode == LockMode.ORIENTED) {
            return applyOrientedEdgeEndpointEdits(edge, snap);
        }
        boolean mutated = false;
        mutated |= applyEdgeEndpoint(edge.getStart(), snap, FIELD_START_X, FIELD_START_Y);
        mutated |= applyEdgeEndpoint(edge.getEnd(), snap, FIELD_END_X, FIELD_END_Y);
        return mutated;
    }

    private static boolean applyOrientedEdgeEndpointEdits(CircuitEdge edge,
                                                          com.minecart.ui.panel.PanelSnapshot snap) {
        CircuitNode s = edge.getStart();
        CircuitNode e = edge.getEnd();
        PositionInfo sp = s != null ? s.getInfo(AllElementInfos.POSITION) : null;
        PositionInfo ep = e != null ? e.getInfo(AllElementInfos.POSITION) : null;
        if (sp == null || ep == null) {
            return false;
        }
        Double sx = snap.getDouble(FIELD_START_X).orElse(null);
        Double sy = snap.getDouble(FIELD_START_Y).orElse(null);
        Double ex = snap.getDouble(FIELD_END_X).orElse(null);
        Double ey = snap.getDouble(FIELD_END_Y).orElse(null);
        if (sx == null || sy == null || ex == null || ey == null) {
            return false;
        }
        if (!Double.isFinite(sx) || !Double.isFinite(sy)
                || !Double.isFinite(ex) || !Double.isFinite(ey)) {
            return false;
        }
        double sdx = sx - sp.getX();
        double sdy = sy - sp.getY();
        double edx = ex - ep.getX();
        double edy = ey - ep.getY();
        if (Math.abs(sdx - edx) > 1e-6 || Math.abs(sdy - edy) > 1e-6) {
            return false;
        }
        if (sdx == 0.0 && sdy == 0.0) {
            return false;
        }
        boolean mutated = false;
        mutated |= applyEdgeEndpoint(s, snap, FIELD_START_X, FIELD_START_Y);
        mutated |= applyEdgeEndpoint(e, snap, FIELD_END_X, FIELD_END_Y);
        return mutated;
    }
}
