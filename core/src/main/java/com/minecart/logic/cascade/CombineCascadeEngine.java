package com.minecart.logic.cascade;

import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerWorld;
import com.minecart.logic.physics.CircuitPhysicsAdapter;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.LockMode;
import com.minecart.variant.info.LockState;
import com.minecart.variant.info.PositionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Plans and applies an atomic combine-cascade. Handles the new cross-component port-on-port case
 * (translate one component so its port slot can be filled by the survivor); delegates the simple
 * cases (free-on-free, free-on-port, same-component port-on-port) to
 * {@link ServerWorld#combineNodes(CircuitNode, CircuitNode)} so the existing single-pair tests stay
 * authoritative.
 *
 * <h2>Direction policy</h2>
 * Identical to {@link ServerWorld#combineNodes}: when exactly one side is a port of its component,
 * the port wins (becomes survivor) regardless of caller-passed argument order. When BOTH sides are
 * ports of different components — the new cascade case — direction is picked from each component's
 * effective {@link LockState}: whichever side's component can translate becomes the absorbed
 * side, because that's the component that has to move. If neither can translate the plan fails
 * with {@link CombineCascadePlan.Reason#LOCK_CONFLICT}. If both can, the engine keeps the original
 * argument order (matching the user-spec default "the component whose port was 'absorbed' (slot
 * swapped) translates").
 *
 * <h2>What this engine does NOT do (yet)</h2>
 * Transitive cascade — i.e. after translating component A, if A's OTHER ports now coincide with
 * other free nodes / ports of OTHER components, the user-spec says those should auto-merge if the
 * types match and {@code canCombine} agrees, otherwise just visually overlap. The current plan
 * stops after the first translate+swap+rewire+destroy sequence. Transitive cascade is parked
 * behind a {@code TODO(phase-2b-transitive)} marker below.
 */
public final class CombineCascadeEngine {

    private static final Logger log = LoggerFactory.getLogger(CombineCascadeEngine.class);

    private static final double PIVOT_EPSILON = 1e-6;

    private CombineCascadeEngine() {}

    /**
     * Plans the cascade ops needed to combine {@code a} and {@code b}. Returns a successful plan
     * whose {@code ops} list, when applied in order through {@link #apply}, leaves the world in
     * the merged state — or a failure {@link CombineCascadePlan.Reason}.
     *
     * <p>For the simple cases this returns a plan containing a single {@link DelegateCombineOp}
     * that just calls {@link ServerWorld#combineNodes}; the plan abstraction is preserved so the
     * network handler can treat all cases uniformly.
     */
    public static CombineCascadePlan plan(ServerWorld world, CircuitNode a, CircuitNode b) {
        if (world == null || a == null || b == null) {
            return CombineCascadePlan.failure(CombineCascadePlan.Reason.INVALID_INPUT);
        }
        if (a == b) {
            return CombineCascadePlan.failure(CombineCascadePlan.Reason.INVALID_INPUT);
        }
        if (a.getWorld() != world || b.getWorld() != world) {
            return CombineCascadePlan.failure(CombineCascadePlan.Reason.INVALID_INPUT);
        }
        if (!a.canCombine(b) || !b.canCombine(a)) {
            return CombineCascadePlan.failure(CombineCascadePlan.Reason.CANNOT_COMBINE);
        }

        // Classify each input. A "port" here means: belongs to at least one component and is a
        // registered port of one of its owners. Multi-owner ports (Phase 2a) currently bail to
        // SHARED_NODE_UNSUPPORTED — the geometry of "translate component X without dragging Y"
        // when both own the same node needs the transitive cascade we haven't built yet.
        boolean aShared = a.getComponents().size() > 1;
        boolean bShared = b.getComponents().size() > 1;
        if (aShared || bShared) {
            return CombineCascadePlan.failure(CombineCascadePlan.Reason.SHARED_NODE_UNSUPPORTED);
        }
        CircuitComponent aComp = portOwnerOf(a);
        CircuitComponent bComp = portOwnerOf(b);

        boolean aIsPort = aComp != null;
        boolean bIsPort = bComp != null;

        // Cross-component port-on-port → cascade.
        if (aIsPort && bIsPort && aComp != bComp) {
            return planCrossComponentCascade(world, a, aComp, b, bComp);
        }

        // Everything else (free-on-free, free-on-port, same-component port-on-port) → delegate.
        // The delegate op faithfully recreates whatever combineNodes() does today, so the existing
        // test surface stays intact while the engine is the public entry point.
        return CombineCascadePlan.success(
                List.of(new DelegateCombineOp(a, b)));
    }

    /**
     * Applies the ops list in order. On any op returning {@code false}, walks the already-applied
     * ops in reverse and calls {@code undo} on each. Returns {@code true} only if every op
     * succeeded. The world's broadcast pipeline is left to fire as a side-effect of the underlying
     * mutations (ServerWorld.* methods post their own events); the engine doesn't add a synthetic
     * "cascade committed" event because clients reconstruct state from the ordered op stream.
     */
    public static boolean apply(ServerWorld world, List<CascadeOp> ops) {
        if (world == null || ops == null || ops.isEmpty()) {
            return false;
        }
        List<CascadeOp> applied = new ArrayList<>(ops.size());
        for (CascadeOp op : ops) {
            boolean ok;
            try {
                ok = op.apply(world);
            } catch (RuntimeException ex) {
                log.error("cascade op {} threw on apply", op.getClass().getSimpleName(), ex);
                ok = false;
            }
            if (!ok) {
                log.warn("cascade aborted at op {} ({} prior ops applied) — rolling back",
                        op.getClass().getSimpleName(), applied.size());
                rollback(world, applied);
                return false;
            }
            applied.add(op);
        }
        return true;
    }

    /** Convenience: plan then apply. */
    public static boolean tryCombine(ServerWorld world, CircuitNode a, CircuitNode b) {
        CombineCascadePlan p = plan(world, a, b);
        if (!p.isSuccess()) {
            return false;
        }
        return apply(world, p.getOps());
    }

    /**
     * Atomically translates a component by {@code (dx, dy)}, after a lock-state preflight: refuses
     * (returns {@code false}) when the component's effective lock doesn't allow translation. The
     * translation is dispatched through {@link CircuitPhysicsAdapter#translate} so any rigid
     * edges and multi-owner port nodes connected to {@code component} propagate the motion
     * across the kinematic sub-graph; isolated components (the common case for the editor's
     * single-element drag) move only themselves because no constraints exist to pull anything
     * else along.
     *
     * <p>Notifies via {@link com.minecart.foundation.Level#notifyElementChanged} for every
     * mutated element — the adapter handles the per-body notifies internally — so the standard
     * delta-sync replicates the move to client mirrors. We don't broadcast on a failed preflight:
     * the world is unchanged.
     */
    public static boolean tryTranslateComponent(ServerWorld world, CircuitComponent component,
                                                double dx, double dy) {
        if (world == null || component == null) {
            return false;
        }
        if (dx == 0.0 && dy == 0.0) {
            return true; // identity — succeeds without side effects.
        }
        LockState eff = component.effectiveLockState(PIVOT_EPSILON);
        if (!eff.mode().allowsTranslation()) {
            broadcastAuthoritativeComponent(world, component);
            return false;
        }
        CircuitPhysicsAdapter.translate(world, component, dx, dy);
        return true;
    }

    /**
     * Atomically rotates a component by {@code deltaRadians} around {@code (pivotX, pivotY)} via
     * {@link CircuitPhysicsAdapter#rotate}, with the same lock-preflight semantics as
     * {@link #tryTranslateComponent}. The pivot is the geometric centre of rotation, separate from
     * the component's centre {@link PositionInfo}: gestures and the panel-driven motion paths use
     * this directly with the cursor or user-chosen pivot.
     *
     * <p>ROTATION_FREE strict additionally constrains the pivot: it must coincide (within
     * {@link #PIVOT_EPSILON}) with the authored strict pivot. A gesture targeting a different
     * pivot is refused outright rather than partially applied — matches the user's "rotation
     * lock always wins" contract for the seed body. Bodies <em>elsewhere</em> in the chain with
     * rotation-locked inverse inertias also refuse to spin, but that's enforced naturally by the
     * solver's inverse-inertia weighting (no special handling needed here).
     */
    public static boolean tryRotateComponent(ServerWorld world, CircuitComponent component,
                                             double pivotX, double pivotY, double deltaRadians) {
        if (world == null || component == null) {
            return false;
        }
        if (deltaRadians == 0.0) {
            return true;
        }
        LockState eff = component.effectiveLockState(PIVOT_EPSILON);
        if (!eff.mode().allowsRotation()) {
            broadcastAuthoritativeComponent(world, component);
            return false;
        }
        if (eff.mode() == LockMode.ROTATION_FREE && eff.pivotValid()) {
            if (Math.abs(eff.pivotX() - pivotX) > PIVOT_EPSILON
                    || Math.abs(eff.pivotY() - pivotY) > PIVOT_EPSILON) {
                broadcastAuthoritativeComponent(world, component);
                return false;
            }
        }
        CircuitPhysicsAdapter.rotate(world, component, pivotX, pivotY, deltaRadians);
        return true;
    }

    /**
     * Higher-level helper used by the node / edge panel fragments: the player typed a new world
     * position into a PORT node's coordinate field, so we translate the parent component to bring
     * that port to the requested position. Reduces to {@link #tryTranslateComponent} after the
     * geometry is solved.
     *
     * <p>Refuses when {@code portNode} isn't a port (no parent component), when it's a port of
     * more than one component (the shared-port case is handled by emitting the parent translation
     * and letting the pin-joint constraint propagate via the adapter — but only when the caller
     * picks one specific owner), or when the parent component's effective lock state forbids
     * translation.
     */
    public static boolean tryMovePortNode(ServerWorld world, CircuitNode portNode,
                                          double targetX, double targetY) {
        if (world == null || portNode == null) {
            return false;
        }
        if (portNode.getComponents().size() != 1) {
            // 0 → free node (caller should write PositionInfo directly, not call us).
            // ≥2 → shared port between components; needs a deliberate "which owner moves" choice.
            broadcastAuthoritativeNode(world, portNode);
            return false;
        }
        CircuitComponent parent = portNode.getComponents().iterator().next();
        if (!parent.isPort(portNode)) {
            broadcastAuthoritativeNode(world, portNode);
            return false;
        }
        PositionInfo p = portNode.getInfo(AllElementInfos.POSITION);
        if (p == null) {
            broadcastAuthoritativeNode(world, portNode);
            return false;
        }
        double dx = targetX - p.getX();
        double dy = targetY - p.getY();
        return tryTranslateComponent(world, parent, dx, dy);
    }

    /**
     * Free-node analogue of {@link #tryTranslateComponent}: dispatches a free node's translation
     * gesture through the physics adapter so it propagates across any rigid edges incident to
     * the node. Refuses silently when {@code node} is actually a port (the caller should route
     * port edits through {@link #tryMovePortNode}) or when its {@link PositionInfo#isFixed()}
     * flag forbids movement.
     */
    public static boolean tryTranslateFreeNode(ServerWorld world, CircuitNode node,
                                                double dx, double dy) {
        if (world == null || node == null) {
            return false;
        }
        if (!node.getComponents().isEmpty()) {
            broadcastAuthoritativeNode(world, node);
            return false;
        }
        if (dx == 0.0 && dy == 0.0) {
            return true;
        }
        PositionInfo p = node.getInfo(AllElementInfos.POSITION);
        if (p != null && p.isFixed()) {
            broadcastAuthoritativeNode(world, node);
            return false;
        }
        CircuitPhysicsAdapter.translateFreeNode(world, node, dx, dy);
        return true;
    }

    /**
     * Emits an authoritative-pose sync for {@code component} and every internal node it owns. Used
     * after a refused translate / rotate so the client mirror — which has already optimistically
     * moved the component's centre and every internal port in {@code DragController.applyComponentDragDelta} —
     * gets corrected back to the server's authoritative state.
     *
     * <p>The set notified here intentionally mirrors what the optimistic client-side drag mutates
     * (the component itself plus {@code comp.getNodes()}). Notifying the component without its
     * internal nodes would leave the ports rendered at the dragged offset even after the centre
     * snapped back, since the sync layer can't infer the rigid relationship and clients don't
     * re-derive port positions from a component's POSITION delta.
     *
     * <p>The sync layer ({@link com.minecart.server.listener.CircuitElementListener}) deduplicates
     * by element id and serializes the current state at flush time, so calling this with an
     * unchanged authoritative pose still produces a meaningful CHANGE delta that overwrites the
     * client's stale optimistic state — the whole point of the rebroadcast.
     */
    private static void broadcastAuthoritativeComponent(ServerWorld world, CircuitComponent component) {
        if (world == null || component == null) {
            return;
        }
        com.minecart.foundation.Level level = world.getLevel();
        if (level == null) {
            return;
        }
        level.notifyElementChanged(component);
        for (CircuitNode internal : component.getNodes()) {
            if (internal != null) {
                level.notifyElementChanged(internal);
            }
        }
    }

    /**
     * Single-element variant of {@link #broadcastAuthoritativeComponent}: emits one CHANGE for a
     * free node (or a refused port-node move where the engine doesn't reach the parent component).
     */
    private static void broadcastAuthoritativeNode(ServerWorld world, CircuitNode node) {
        if (world == null || node == null) {
            return;
        }
        com.minecart.foundation.Level level = world.getLevel();
        if (level == null) {
            return;
        }
        level.notifyElementChanged(node);
    }

    private static void rollback(ServerWorld world, List<CascadeOp> applied) {
        for (int i = applied.size() - 1; i >= 0; i--) {
            CascadeOp op = applied.get(i);
            try {
                op.undo(world);
            } catch (RuntimeException ex) {
                // Undo failures leave the world in a degraded state. Log loudly; we don't have a
                // better recovery than to bail and surface the cascade as failed.
                log.error("cascade undo of {} threw — world state may be inconsistent",
                        op.getClass().getSimpleName(), ex);
            }
        }
    }

    /**
     * Builds the cascade plan for the cross-component port-on-port case. Direction policy:
     * <ol>
     *   <li>If both component lock states forbid translation → fail with LOCK_CONFLICT.</li>
     *   <li>If only one of the two can translate → that one becomes the absorbed (slot-swap)
     *       side, regardless of original argument order — only the movable component is asked to
     *       move.</li>
     *   <li>If both can translate → keep the original a/b roles (b becomes the absorbed,
     *       translated component). Matches the user-spec default.</li>
     * </ol>
     * Then computes delta, plans translate → switchPort → rewire-incident-edges → destroy.
     */
    private static CombineCascadePlan planCrossComponentCascade(
            ServerWorld world,
            CircuitNode survivorIn, CircuitComponent survivorCompIn,
            CircuitNode absorbedIn, CircuitComponent absorbedCompIn) {

        CircuitNode survivor = survivorIn;
        CircuitNode absorbed = absorbedIn;
        CircuitComponent survivorComp = survivorCompIn;
        CircuitComponent absorbedComp = absorbedCompIn;

        // Type ids must match — port slot identity is preserved across the swap.
        if (!java.util.Objects.equals(survivor.getRegistryTypeId(), absorbed.getRegistryTypeId())) {
            return CombineCascadePlan.failure(CombineCascadePlan.Reason.TYPE_MISMATCH);
        }

        LockState survivorLock = survivorComp.effectiveLockState(PIVOT_EPSILON);
        LockState absorbedLock = absorbedComp.effectiveLockState(PIVOT_EPSILON);

        boolean survivorCanTranslate = survivorLock.mode().allowsTranslation();
        boolean absorbedCanTranslate = absorbedLock.mode().allowsTranslation();

        if (!survivorCanTranslate && !absorbedCanTranslate) {
            return CombineCascadePlan.failure(CombineCascadePlan.Reason.LOCK_CONFLICT);
        }

        // If only survivor's comp can translate, invert roles so the movable comp is the one we
        // translate. After the swap, "absorbed" refers to the port-on-the-movable-comp.
        if (!absorbedCanTranslate && survivorCanTranslate) {
            CircuitNode tmpN = survivor; survivor = absorbed; absorbed = tmpN;
            CircuitComponent tmpC = survivorComp; survivorComp = absorbedComp; absorbedComp = tmpC;
        }

        // Recompute port indices on the now-canonical sides.
        int absorbedPortIdx = absorbedComp.portIndexOf(absorbed);
        int survivorPortIdx = survivorComp.portIndexOf(survivor);
        if (absorbedPortIdx < 0 || survivorPortIdx < 0) {
            return CombineCascadePlan.failure(CombineCascadePlan.Reason.INTERNAL);
        }

        PositionInfo survivorPos = survivor.getInfo(AllElementInfos.POSITION);
        PositionInfo absorbedPos = absorbed.getInfo(AllElementInfos.POSITION);
        if (survivorPos == null || absorbedPos == null) {
            return CombineCascadePlan.failure(CombineCascadePlan.Reason.INTERNAL);
        }

        double dx = survivorPos.getX() - absorbedPos.getX();
        double dy = survivorPos.getY() - absorbedPos.getY();

        List<CascadeOp> ops = new ArrayList<>(4);

        // Step 1: translate the absorbed component (centre + every internal node except `absorbed`
        // itself, since it's about to be replaced/destroyed). After this step absorbedComp's
        // remaining ports / internals are shifted by (dx, dy) so the new port slot will sit on
        // `survivor`'s world position.
        if (dx != 0.0 || dy != 0.0) {
            Set<CircuitNode> excluded = new LinkedHashSet<>();
            excluded.add(absorbed);
            ops.add(new TranslateComponentOp(absorbedComp, dx, dy, excluded));
        }

        // Step 2: slot-swap absorbed → survivor on absorbedComp at absorbedPortIdx.
        ops.add(new SwitchPortOp(absorbedComp, absorbedPortIdx, survivor));

        // Step 3: rewire every incident edge of `absorbed` to point at `survivor` instead, or
        // disconnect when both ends would coincide on survivor (self-loop).
        for (CircuitEdge incident : new ArrayList<>(absorbed.getConnection())) {
            CircuitNode other = incident.getOther(absorbed);
            if (other == survivor) {
                ops.add(new DisconnectEdgeOp(incident));
                continue;
            }
            CircuitNode newStart = incident.getStart() == absorbed ? survivor : incident.getStart();
            CircuitNode newEnd = incident.getEnd() == absorbed ? survivor : incident.getEnd();
            ops.add(new RewireEdgeOp(incident, newStart, newEnd));
        }

        // Step 4: destroy the orphaned `absorbed` node. After step 2 it's no longer in any
        // component's port slot, and after step 3 no edge references it, so the destroy hits the
        // free-node path cleanly.
        ops.add(new DestroyNodeOp(absorbed));

        // TODO(phase-2b-transitive): after the translation in step 1, any of absorbedComp's OTHER
        // ports may now coincide with free nodes / ports of other components. The user-spec says
        // those should auto-cascade if the types match and canCombine agrees, otherwise overlap
        // visually. Implementing that means re-running plan() on each newly-coincident pair and
        // appending those ops here. Held until the single-step cascade is verified end-to-end.

        return CombineCascadePlan.success(ops);
    }

    /**
     * @return the unique component for which {@code node} sits at one of its port indices, or
     *         {@code null} if {@code node} is free / internal-only. Multi-owner ports are caught
     *         earlier by the {@code SHARED_NODE_UNSUPPORTED} guard.
     */
    private static CircuitComponent portOwnerOf(CircuitNode node) {
        for (CircuitComponent c : node.getComponents()) {
            if (c.isPort(node)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Internal one-op delegator for the cases the engine doesn't natively handle yet (free-on-X,
     * same-component port-on-port). Lets {@link #plan} return a uniform op list for any input.
     */
    private record DelegateCombineOp(CircuitNode a, CircuitNode b) implements CascadeOp {

        @Override
        public boolean apply(ServerWorld world) {
            return world.combineNodes(a, b);
        }

        @Override
        public void undo(ServerWorld world) {
            // Delegate combine is currently the last (and only) op in its plan, so undo is never
            // reached — see Engine.apply. If this ever changes, undoing combineNodes is non-trivial
            // (recreate the destroyed node, re-attach edges, restore port slot). We refuse rather
            // than attempt a partial rebuild.
            throw new UnsupportedOperationException(
                    "Cannot undo a delegate combineNodes op; place it last in any plan");
        }
    }
}
