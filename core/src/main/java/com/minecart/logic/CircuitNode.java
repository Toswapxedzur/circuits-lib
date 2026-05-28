package com.minecart.logic;

import com.minecart.misc.CoreStrings;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.logic.cascade.CombineCascadeEngine;
import com.minecart.math.DoubleVar;
import com.minecart.math.LinearSystem;
import com.minecart.registry.AllElementInfos;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.ui.panel.InfoPanelRegistry;
import com.minecart.ui.panel.fields.CheckboxSpec;
import com.minecart.ui.panel.fields.NumberFieldSpec;
import com.minecart.variant.info.PositionInfo;

import java.util.*;

public non-sealed class CircuitNode extends CircuitElement {
    protected DoubleVar voltage;
    protected Set<CircuitEdge> connection;
    /**
     * Owning components. A node is "owned" by every component whose port slot it occupies. The set
     * is single-element for the common case (free node = empty, internal-port node = one component);
     * Phase 2b's port-port merge across two components produces the multi-owner case where the same
     * node sits in both components' port maps. Iteration order matches insertion order to keep
     * downstream consumers (renderer hide rules, save/load, sync deltas) deterministic.
     *
     * <p>The single-owner legacy API {@link #getComponent()} / {@link #setComponent(CircuitComponent)}
     * still works: getComponent returns the first owner (or null), setComponent collapses to a
     * single owner. New code in the combine engine uses {@link #addComponent} /
     * {@link #removeComponent} which manage individual entries without disturbing the others.
     */
    protected final Set<CircuitComponent> components = new LinkedHashSet<>();
    protected boolean ground;

    public CircuitNode(World world){
        setWorld(world);
        voltage = DoubleVar.create();
        connection = new LinkedHashSet<>();
    }

    @Override
    public void collectVariable(Set<DoubleVar> variables) {
        super.collectVariable(variables);
        variables.add(this.voltage);
    }

    @Override
    public void collectRule(LinearSystem.RelationProvider equations) {
        super.collectRule(equations);

        //Ground node don't provide kirchoff current rule, but its voltage is always zero
        if(isGrounded()){
            equations.stampCoefficient(this.voltage, 1);
            equations.stampConstant(0.0);
            equations.endRelation();
            return;
        }

        //implementation of default kirchoff current rule
        if (connection.isEmpty()) return;
        for (CircuitEdge edge : connection) {
            double coef = edge.shouldRevert(this) ? -1.0 : 1.0;
            equations.stampCoefficient(edge.getCurrent(), coef);
        }
        equations.stampConstant(0.0);
        equations.endRelation();
    }

    @Override
    public void tick(){

    }

    public boolean isGrounded() {
        return ground;
    }

    protected void setGround(boolean ground) {
        this.ground = ground;
    }

    /**
     * Only modify information in the scope of circuit node itself, do not modify field in the circuit and world.
     */
    public boolean connectEdge(CircuitEdge egde, boolean simulate){
        if(!simulate)
            connection.add(egde);
        return true;
    }

    /**
     * Only modify information in the scope of circuit node itself, do not modify field in the circuit and world.
     */
    public boolean disconnect(CircuitEdge edge, boolean simulate){
        if(simulate)
            return connection.contains(edge);
        return connection.remove(edge);
    }

    public Set<CircuitEdge> getConnection(){
        return Collections.unmodifiableSet(this.connection);
    }

    public boolean hasComponent() {
        return !components.isEmpty();
    }

    /**
     * @return the primary (first-added) owning component, or {@code null} if this node is free.
     *         Single-owner legacy API; multi-owner consumers should use {@link #getComponents()}.
     *         When a node is a shared port across multiple components (Phase 2b cascade outcome),
     *         this method returns the one that adopted it earliest — sufficient for most legacy
     *         "what component am I part of" lookups but not for iterating all owners.
     */
    public CircuitComponent getComponent() {
        return components.isEmpty() ? null : components.iterator().next();
    }

    /**
     * Single-owner setter: clears every existing owner and (if non-null) adds {@code component}.
     * Preserves legacy semantics for call sites that placed a node into exactly one component or
     * cleared its parent during destroy. New multi-owner-aware code should call
     * {@link #addComponent} / {@link #removeComponent} directly so unrelated component memberships
     * survive the mutation.
     */
    public void setComponent(CircuitComponent component) {
        components.clear();
        if (component != null) {
            components.add(component);
        }
    }

    /**
     * Read-only view of every component currently claiming this node as one of its ports.
     */
    public Set<CircuitComponent> getComponents() {
        return Collections.unmodifiableSet(components);
    }

    /**
     * Adds {@code component} to the owner set if not already present. Idempotent. Caller must keep
     * the backlink consistent (i.e. also add this node to the component's {@code nodes} and
     * appropriate port slot) — this method only maintains the node-side reference.
     */
    public void addComponent(CircuitComponent component) {
        if (component == null) {
            return;
        }
        components.add(component);
    }

    /**
     * Removes {@code component} from the owner set if present. No-op when this node was never a
     * port of {@code component}. Caller is responsible for tearing down the component-side
     * structures (port slot, {@code nodes} membership) — this method only maintains the node-side
     * reference.
     */
    public void removeComponent(CircuitComponent component) {
        if (component == null) {
            return;
        }
        components.remove(component);
    }

    /** Whether this node is currently a port of {@code component}. */
    public boolean inComponent(CircuitComponent component) {
        return component != null && components.contains(component);
    }

    public Set<CircuitNode> getAdjacent(){
        LinkedHashSet<CircuitNode> adjacent = new LinkedHashSet<>();
        getConnection().forEach(e -> {
            adjacent.add(e.getConnection(0));
            adjacent.add(e.getConnection(1));
        });
        adjacent.remove(this);
        return adjacent;
    }

    /**
     * Whether this node will accept being merged with {@code other} via
     * {@link com.minecart.foundation.World#combineNodes(CircuitNode, CircuitNode)}.
     *
     * <p>Both endpoints' {@code canCombine} must return {@code true} for a combine to proceed — vetoes are
     * symmetric so neither side can be silently overridden by the other. The default rule rejects
     * <em>intrinsic</em> internals (a component-owned node that isn't a registered port) because those are
     * hidden affordances that the rest of the system isn't supposed to interact with at all; everything
     * else (free nodes and exposed ports) opts in. Subclasses with stricter electrical invariants — e.g. a
     * polarised junction that can only merge with its own kind — should override and tighten this.
     */
    public boolean canCombine(CircuitNode other) {
        // Reject if ANY owning component treats this node as a non-port internal (intrinsic
        // helper that's not meant to be user-interactable). Under multi-owner semantics a node
        // can appear in several components' port maps simultaneously, so the rule must veto on
        // the first component that disagrees rather than relying on a single "the" component.
        for (CircuitComponent c : components) {
            if (!c.isPort(this)) {
                return false;
            }
        }
        return true;
    }

    public DoubleVar getVoltage() {
        return voltage;
    }

    @Override
    public void save(CompoundTag tag) {
        super.save(tag);
        tag.putBoolean(CoreStrings.NODE_GROUND, isGrounded());
        tag.putDouble(CoreStrings.NODE_VOLTAGE, getVoltage().getValue());
        if (getComponent() != null) {
            TagUtil.putUUID(tag, CoreStrings.NODE_COMPONENT, getComponent().getId());
        }
    }

    /**
     * Restores ground and voltage from {@code tag}. Registry id is set by {@link CircuitElement#deserialize}
     * before {@link Circuit#addNode} and this call.
     */
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        setGround(tag.getBoolean(CoreStrings.NODE_GROUND));
        getVoltage().setValue(tag.getDouble(CoreStrings.NODE_VOLTAGE));
    }

    /** Snapshot key for the node's authored world position. Editable in the cross-cutting fragment. */
    public static final String FIELD_POSITION_X = "core:position.x";
    public static final String FIELD_POSITION_Y = "core:position.y";
    /**
     * Snapshot key for the node's "is fixed" lock toggle. Nodes have no rotation degree of
     * freedom, so the four-mode {@link com.minecart.variant.info.LockMode} collapses to this
     * 2-state via {@link com.minecart.variant.info.LockMode#forNode()} (POSITION_FREE ≡ FREE,
     * ROTATION_FREE ≡ LOCKED) — the panel exposes the collapsed form directly.
     */
    public static final String FIELD_LOCK_FIXED = "core:lock.isFixed";

    static {
        // Cross-cutting fragment for every CircuitNode: position (x, y) editable when the node's
        // PositionInfo isn't hard-locked (canChangeFix=false). Port nodes are shown editable too,
        // but in Phase 3a the save handler silently refuses port edits; Phase 3b will route them
        // through the cascade engine to translate the parent component.
        InfoPanelRegistry.registerNodeFragment((node, builder) -> {
            PositionInfo pos = node.getInfo(AllElementInfos.POSITION);
            if (pos == null) {
                return;
            }
            boolean hardLocked = !pos.canChangeFix() && pos.isFixed();
            if (!hardLocked) {
                builder.add(new NumberFieldSpec(FIELD_POSITION_X, "X", pos.getX()));
                builder.add(new NumberFieldSpec(FIELD_POSITION_Y, "Y", pos.getY()));
            }
            // Lock toggle: shown when the player is allowed to flip it. Hidden when
            // canChangeFix=false (i.e. the lock state itself is permanent — component-anchor
            // internals stamped with canChangeFix=false at place time).
            if (pos.canChangeFix()) {
                builder.add(new CheckboxSpec(FIELD_LOCK_FIXED, "Locked", pos.isFixed()));
            }
        });

        // Save handler: validate + apply, then notify so the change replicates. Port nodes refuse
        // here (Phase 3b will route through the cascade engine); free nodes apply directly.
        InfoPanelRegistry.registerNodeFragmentSaveHandler((node, snapshot, evt) -> {
            PositionInfo pos = node.getInfo(AllElementInfos.POSITION);
            if (pos == null) return;
            boolean mutated = false;

            // Lock toggle: applied first so position edits in the same payload see the new lock
            // state (and refuse to write if the user just locked the node). canChangeFix gates.
            if (pos.canChangeFix()) {
                Boolean wantsFixed = snapshot.getBoolean(FIELD_LOCK_FIXED).orElse(null);
                if (wantsFixed != null && pos.setFixed(wantsFixed)) {
                    mutated = true;
                }
            }

            // Position: free nodes write PositionInfo directly; port nodes route through the
            // cascade engine, which translates the parent component so the port lands at the new
            // world position (Phase 3b). Both paths honour the lock guard: a free node with
            // isFixed=true refuses; a port whose parent component's effective lock disallows
            // translation refuses (returned via tryMovePortNode → tryTranslateComponent).
            Double newX = snapshot.getDouble(FIELD_POSITION_X).orElse(null);
            Double newY = snapshot.getDouble(FIELD_POSITION_Y).orElse(null);
            if (newX != null && newY != null
                    && Double.isFinite(newX) && Double.isFinite(newY)) {
                if (node.getComponents().isEmpty()) {
                    if (!pos.isFixed() && (newX != pos.getX() || newY != pos.getY())) {
                        pos.set(newX, newY);
                        mutated = true;
                    }
                } else if (node.getWorld() instanceof ServerWorld sw) {
                    // Port edit → translate the parent component. Engine notifies its own
                    // element-changed callbacks for the moved nodes + component on success, so
                    // we don't double-notify here. Failure is silent (sync re-asserts).
                    CombineCascadeEngine.tryMovePortNode(sw, node, newX, newY);
                }
            }

            if (mutated && node.getWorld() != null) {
                node.getWorld().getLevel().notifyElementChanged(node);
            }
        });
    }
}
