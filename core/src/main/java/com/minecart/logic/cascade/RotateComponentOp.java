package com.minecart.logic.cascade;

import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.PositionInfo;
import com.minecart.variant.info.RotationInfo;

/**
 * Rigidly rotates a {@link CircuitComponent} by {@code deltaRadians} around {@code (pivotX, pivotY)}.
 * Updates the component's {@link RotationInfo} angle (when present) and the {@link PositionInfo} of
 * every owned internal node — centre, ports, intrinsic non-port internals — by transforming each
 * point around the pivot.
 *
 * <h2>Shared / multi-owner ports</h2>
 * Skipped, same rationale as {@link TranslateComponentOp}: a port owned by two components can't be
 * rotated by just one of them without violating the other's rigid anchor. The cascade engine refuses
 * such cases up-front (Phase 2c will revisit). The op itself silently skips multi-owner nodes so
 * the apply never tugs at a shared node.
 *
 * <h2>Centre rotation</h2>
 * The component's centre PositionInfo rotates around the pivot the same way every other internal
 * point does. When the pivot IS the centre (the common case for the panel's "Rotation (rad)" edit),
 * the centre's transformed position equals its input position — i.e. a no-op for the centre while
 * the ports orbit. The op handles this naturally because the rotation transform of a point at the
 * pivot is the pivot itself.
 */
public final class RotateComponentOp implements CascadeOp {

    private final CircuitComponent component;
    private final double pivotX;
    private final double pivotY;
    private final double deltaRadians;
    /** Snapshot of the nodes we actually transformed, for undo. */
    private final java.util.List<CircuitNode> rotated = new java.util.ArrayList<>();
    private boolean centreRotated;

    public RotateComponentOp(CircuitComponent component, double pivotX, double pivotY,
                             double deltaRadians) {
        this.component = component;
        this.pivotX = pivotX;
        this.pivotY = pivotY;
        this.deltaRadians = deltaRadians;
    }

    @Override
    public boolean apply(ServerWorld world) {
        if (component == null) {
            return false;
        }
        if (deltaRadians == 0.0) {
            return true; // identity
        }
        rotated.clear();
        centreRotated = false;
        double cos = Math.cos(deltaRadians);
        double sin = Math.sin(deltaRadians);

        // Rotate the centre PositionInfo (its angle around the pivot may or may not be zero —
        // when pivot == centre it stays put).
        PositionInfo centre = component.getInfo(AllElementInfos.POSITION);
        if (centre != null) {
            rotatePoint(centre, cos, sin);
            centreRotated = true;
        }

        // Update the component's RotationInfo. When the component has no RotationInfo (free node
        // graph degenerate case) we silently skip — the visual rotation can't be advanced but the
        // internal nodes still rotate around the pivot which is the user's intent for a panel edit.
        RotationInfo rot = component.getInfo(AllElementInfos.ROTATION);
        if (rot != null) {
            rot.setAngle(rot.getAngle() + deltaRadians);
        }

        // Each owned internal node, skipping shared (multi-owner) ports.
        for (CircuitNode n : component.getNodes()) {
            if (n == null) continue;
            if (n.getComponents().size() > 1) continue;
            PositionInfo p = n.getInfo(AllElementInfos.POSITION);
            if (p == null) continue;
            rotatePoint(p, cos, sin);
            rotated.add(n);
        }
        return true;
    }

    @Override
    public void undo(ServerWorld world) {
        if (deltaRadians == 0.0) return;
        double cos = Math.cos(-deltaRadians);
        double sin = Math.sin(-deltaRadians);
        if (centreRotated) {
            PositionInfo centre = component.getInfo(AllElementInfos.POSITION);
            if (centre != null) {
                rotatePoint(centre, cos, sin);
            }
            centreRotated = false;
        }
        RotationInfo rot = component.getInfo(AllElementInfos.ROTATION);
        if (rot != null) {
            rot.setAngle(rot.getAngle() - deltaRadians);
        }
        for (CircuitNode n : rotated) {
            PositionInfo p = n.getInfo(AllElementInfos.POSITION);
            if (p == null) continue;
            rotatePoint(p, cos, sin);
        }
        rotated.clear();
    }

    /** Rotates {@code p} around {@code (pivotX, pivotY)} by the angle whose cos/sin are given. */
    private void rotatePoint(PositionInfo p, double cos, double sin) {
        double rx = p.getX() - pivotX;
        double ry = p.getY() - pivotY;
        p.set(pivotX + rx * cos - ry * sin, pivotY + rx * sin + ry * cos);
    }

    @Override
    public java.util.List<com.minecart.logic.CircuitElement> movedElements() {
        if (component == null || deltaRadians == 0.0) {
            return java.util.List.of();
        }
        java.util.List<com.minecart.logic.CircuitElement> moved =
                new java.util.ArrayList<>(rotated.size() + 1);
        moved.add(component);
        moved.addAll(rotated);
        return moved;
    }

    public CircuitComponent getComponent() {
        return component;
    }

    public double getDeltaRadians() {
        return deltaRadians;
    }
}
