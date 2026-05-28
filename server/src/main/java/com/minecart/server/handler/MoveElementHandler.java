package com.minecart.server.handler;

import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.MoveElementPayload;
import com.minecart.registry.AllElementInfos;
import com.minecart.registry.CircuitElementRegistry;
import com.minecart.registry.CircuitElementType;
import com.minecart.registry.ComponentAnchorRegistry;
import com.minecart.variant.info.LockState;
import com.minecart.variant.info.PositionInfo;
import com.minecart.variant.info.RotationInfo;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Server-side handler for {@link MoveElementPayload}: re-positions a free node or a component (and all its
 * internal nodes, port-anchored or not) and notifies the element-change pipeline so the move replicates to
 * every client mirror via the existing sync stream.
 *
 * <p>Silent no-op on unknown world, unknown element, or element kind that doesn't carry a single anchor
 * (edges are positionless by construction and rely on their endpoints).
 */
public final class MoveElementHandler implements PayloadHandler<MoveElementPayload> {

    /**
     * Tolerance fed to {@link CircuitComponent#effectiveLockState(double)} for the lock-state
     * preflight. Same value the cascade engine uses for pivot reconciliation — kept inline here
     * to avoid coupling the handler to an unrelated module-private constant.
     */
    private static final double LOCK_EPSILON = 1e-6;

    private final ServerLevel level;

    public MoveElementHandler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void handle(MoveElementPayload payload) {
        level.submit(() -> apply(payload));
    }

    private void apply(MoveElementPayload payload) {
        World world = level.findWorld(payload.getWorldId());
        if (!(world instanceof ServerWorld)) {
            return;
        }
        CircuitElement el = findElement(world, payload.getElementId());
        if (el == null) {
            return;
        }
        if (el instanceof CircuitComponent comp) {
            moveComponent(comp, payload.getX(), payload.getY());
        } else if (el instanceof CircuitNode node) {
            moveFreeNode(node, payload.getX(), payload.getY());
        }
    }

    private void notifyChanged(CircuitElement el) {
        World w = el.getWorld();
        if (w != null) {
            w.getLevel().notifyElementChanged(el);
        }
    }

    private static CircuitElement findElement(World world, java.util.UUID id) {
        for (Circuit c : world.getCircuits()) {
            CircuitElement el = c.findElement(id);
            if (el != null) {
                return el;
            }
        }
        return null;
    }

    /**
     * Moves the component's centre and re-stamps every internal node (port nodes via the anchor registry,
     * non-port internals at the new centre). Sync data is emitted as PositionInfo lives in the element's
     * info bag, so each {@link CircuitElement#notifyElementChanged()} call flows through
     * {@link com.minecart.server.listener.CircuitElementListener} as a CHANGE op for that node.
     */
    private void moveComponent(CircuitComponent comp, double x, double y) {
        // Phase 1 lock enforcement: refuse the drag silently when the component's effective lock
        // doesn't permit translation. Mirror reads "no delta" → drag visually snaps back. The
        // engine-level entry point ({@link com.minecart.logic.cascade.CombineCascadeEngine#tryTranslateComponent})
        // already enforces the same check; we add it here so the manual move path is consistent
        // with the cascade path rather than letting LockMode.LOCKED leak through.
        LockState eff = comp.effectiveLockState(LOCK_EPSILON);
        if (!eff.mode().allowsTranslation()) {
            return;
        }
        PositionInfo centre = comp.getInfo(AllElementInfos.POSITION);
        if (centre == null) {
            centre = new PositionInfo();
            comp.setInfo(AllElementInfos.POSITION, centre);
        }
        centre.set(x, y);
        RotationInfo rot = comp.getInfo(AllElementInfos.ROTATION);
        double angle = rot != null ? rot.getAngle() : 0.0;

        CircuitElementType<?> type = CircuitElementRegistry.getType(comp.getRegistryTypeId());
        Set<CircuitNode> anchored = new HashSet<>();
        if (type != null) {
            for (ComponentAnchorRegistry.Anchor anchor : ComponentAnchorRegistry.getAnchors(type)) {
                CircuitNode port = comp.getPort(anchor.portIndex());
                if (port == null) {
                    continue;
                }
                anchored.add(port);
                double[] xy = ComponentAnchorRegistry.worldPositionOf(anchor, x, y, angle);
                PositionInfo p = port.getInfo(AllElementInfos.POSITION);
                if (p == null) {
                    p = new PositionInfo();
                    port.setInfo(AllElementInfos.POSITION, p);
                }
                p.set(xy[0], xy[1]);
                lockToParent(p);
                notifyChanged(port);
            }
        }
        for (CircuitNode internal : comp.getNodes()) {
            if (anchored.contains(internal)) {
                continue;
            }
            PositionInfo p = internal.getInfo(AllElementInfos.POSITION);
            if (p == null) {
                p = new PositionInfo();
                internal.setInfo(AllElementInfos.POSITION, p);
            }
            p.set(x, y);
            lockToParent(p);
            notifyChanged(internal);
        }
        notifyChanged(comp);
    }

    private void moveFreeNode(CircuitNode node, double x, double y) {
        // Free nodes (placed via PlaceNodeHandler) live outside any component; nodes parented to a component
        // are repositioned through moveComponent so their anchor offsets stay consistent. We skip those here
        // to avoid letting the editor drag a single port off its anchor.
        if (node.getComponent() != null) {
            return;
        }
        PositionInfo p = node.getInfo(AllElementInfos.POSITION);
        // Defensive: even if the parent linkage was somehow lost, refuse to move a position that's been
        // explicitly fixed (e.g. component internals stamped by the place / move-component path). Pairs
        // with the client-side DragController guard so a stale mirror can't trick the server into dragging
        // a port off its anchor.
        if (p != null && p.isFixed()) {
            return;
        }
        if (p == null) {
            p = new PositionInfo();
            node.setInfo(AllElementInfos.POSITION, p);
        }
        p.set(x, y);
        notifyChanged(node);
    }

    private static void lockToParent(PositionInfo pos) {
        pos.setFixed(true);
        pos.setCanChangeFix(false);
    }
}
