package com.minecart.logic.cascade;

import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.PositionInfo;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Rigidly shifts a {@link CircuitComponent}'s centre plus every internal node (ports and intrinsic
 * non-port internals) by {@code (dx, dy)}. The internal edges follow implicitly because they are
 * positioned by their endpoint nodes — moving the nodes moves the edge lines on the client without
 * any per-edge bookkeeping here.
 *
 * <h2>Shared-port handling</h2>
 * Ports that are owned by multiple components (the Phase 2a multi-owner case) are NOT translated by
 * this op — translating a shared port would tug at every co-owning component, which is the whole
 * point of "the port is the shared anchor". The cascade planner is responsible for not asking us to
 * translate when any port is shared <em>unless</em> every co-owner is being translated together;
 * we just refuse to touch shared ports here as a safety net.
 *
 * <h2>Excluded ports</h2>
 * The optional {@code excludedPorts} set lets the planner say "don't translate <em>this</em>
 * particular port even though it's owned only by {@code component}" — used for the port that's
 * about to be replaced (the absorbed port, which will be destroyed in a later op; translating it
 * first and then destroying it is wasted work and complicates undo).
 */
public final class TranslateComponentOp implements CascadeOp {

    private final CircuitComponent component;
    private final double dx;
    private final double dy;
    private final Set<CircuitNode> excluded;
    /** Snapshot of the nodes we actually translated, captured at {@link #apply} time for undo. */
    private final java.util.List<CircuitNode> translated = new java.util.ArrayList<>();

    public TranslateComponentOp(CircuitComponent component, double dx, double dy) {
        this(component, dx, dy, java.util.Collections.emptySet());
    }

    public TranslateComponentOp(CircuitComponent component, double dx, double dy,
                                Set<CircuitNode> excludedPorts) {
        this.component = component;
        this.dx = dx;
        this.dy = dy;
        this.excluded = excludedPorts != null ? new LinkedHashSet<>(excludedPorts) : new LinkedHashSet<>();
    }

    @Override
    public boolean apply(ServerWorld world) {
        if (component == null) {
            return false;
        }
        if (dx == 0.0 && dy == 0.0) {
            // Empty translation: still "succeeds" — undo is a no-op — but skip the body so we
            // don't accumulate excluded/included bookkeeping work for nothing.
            return true;
        }
        translated.clear();
        // Centre PositionInfo: this is the component's own anchor and isn't shared, so always shift.
        PositionInfo centre = component.getInfo(AllElementInfos.POSITION);
        if (centre != null) {
            centre.set(centre.getX() + dx, centre.getY() + dy);
        }
        // Internal nodes: every node currently owned by the component. Skip excluded ones; skip
        // shared (multi-owner) nodes as documented above.
        for (CircuitNode n : component.getNodes()) {
            if (n == null || excluded.contains(n)) {
                continue;
            }
            if (n.getComponents().size() > 1) {
                continue;
            }
            PositionInfo p = n.getInfo(AllElementInfos.POSITION);
            if (p == null) {
                continue;
            }
            p.set(p.getX() + dx, p.getY() + dy);
            translated.add(n);
        }
        return true;
    }

    @Override
    public void undo(ServerWorld world) {
        if (dx == 0.0 && dy == 0.0) {
            return;
        }
        PositionInfo centre = component.getInfo(AllElementInfos.POSITION);
        if (centre != null) {
            centre.set(centre.getX() - dx, centre.getY() - dy);
        }
        for (CircuitNode n : translated) {
            PositionInfo p = n.getInfo(AllElementInfos.POSITION);
            if (p == null) {
                continue;
            }
            p.set(p.getX() - dx, p.getY() - dy);
        }
        translated.clear();
    }

    @Override
    public java.util.List<com.minecart.logic.CircuitElement> movedElements() {
        if (component == null || (dx == 0.0 && dy == 0.0)) {
            return java.util.List.of();
        }
        java.util.List<com.minecart.logic.CircuitElement> moved =
                new java.util.ArrayList<>(translated.size() + 1);
        moved.add(component);
        moved.addAll(translated);
        return moved;
    }

    public CircuitComponent getComponent() {
        return component;
    }

    public double getDx() {
        return dx;
    }

    public double getDy() {
        return dy;
    }
}
