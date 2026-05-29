package com.minecart.server.handler;

import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.logic.cascade.CombineCascadeEngine;
import com.minecart.logic.physics.DragGesture;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.MoveElementPayload;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.PositionInfo;

import java.util.Objects;
import java.util.UUID;

/**
 * Server-side handler for {@link MoveElementPayload}. Two routing modes, picked by whether the
 * payload carries a {@code gestureId}:
 *
 * <ul>
 *   <li><b>Streaming drag</b> ({@code gestureId != null}): the payload is one sample from a
 *       client-side drag. It's pushed into the level's {@link com.minecart.logic.physics.DragAggregator}
 *       for resolution by the high-frequency drag pump. Multiple streaming samples
 *       with the same gesture coalesce to the latest; distinct gesture ids on the same element
 *       trigger the contention policy (immobilise for the current flush).</li>
 *   <li><b>One-shot</b> ({@code gestureId == null}): a panel save, scroll-rotate's translation
 *       sibling, or any other discrete mutation. Routed through
 *       {@link CombineCascadeEngine#tryTranslateComponent} (for components) or
 *       {@link CombineCascadeEngine#tryTranslateFreeNode} (for free nodes) immediately — hard pin
 *       at the target pose, propagated across rigid edges synchronously.</li>
 * </ul>
 *
 * <p>Silent no-op on unknown world / unknown element / element kind without a single anchor
 * (edges are positionless by construction). The strict-lock preflight lives in
 * {@link com.minecart.logic.physics.DragAggregator#flush} for the streaming path and in the
 * cascade engine entry points for the one-shot path.
 */
public final class MoveElementHandler implements PayloadHandler<MoveElementPayload> {

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
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        CircuitElement el = findElement(world, payload.getElementId());
        if (el == null) {
            return;
        }
        UUID gestureId = payload.getGestureId();
        if (gestureId != null) {
            // Streaming drag: queue into the aggregator. The high-frequency drag pump flushes
            // buffered gestures in a single unified solve and broadcasts authority.
            level.getDragAggregator().enqueue(payload.getWorldId(),
                    new DragGesture(gestureId, el, payload.getX(), payload.getY()));
            return;
        }
        // One-shot path (panel save, etc.) — hard-pin the target pose synchronously.
        if (el instanceof CircuitComponent comp) {
            moveComponent(serverWorld, comp, payload.getX(), payload.getY());
        } else if (el instanceof CircuitNode node) {
            moveFreeNode(serverWorld, node, payload.getX(), payload.getY());
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
     * Translates the component so its centre lands at {@code (x, y)}. First-time placement on a
     * component without an existing {@link PositionInfo} short-circuits to a direct stamp + notify
     * (no delta to propagate, no constraint graph yet built).
     */
    private void moveComponent(ServerWorld world, CircuitComponent comp, double x, double y) {
        PositionInfo centre = comp.getInfo(AllElementInfos.POSITION);
        if (centre == null) {
            // Bootstrap: the component has no recorded centre yet, so there's no delta to propagate
            // and the constraint graph wouldn't have anything meaningful to do. Stamp directly and
            // hand off to the cascade engine on subsequent moves.
            centre = new PositionInfo();
            comp.setInfo(AllElementInfos.POSITION, centre);
            centre.set(x, y);
            level.notifyElementChanged(comp);
            return;
        }
        double dx = x - centre.getX();
        double dy = y - centre.getY();
        CombineCascadeEngine.tryTranslateComponent(world, comp, dx, dy);
    }

    /**
     * Translates a free node so it lands at {@code (x, y)}. Skips port nodes (port motion is the
     * cascade engine's responsibility via {@link CombineCascadeEngine#tryMovePortNode}) and
     * isFixed=true nodes (player-locked node anchor).
     *
     * <p>The short-circuit refusals (port node, isFixed) still fire a {@link com.minecart.foundation.Level#notifyElementChanged}
     * for the node — under the current server-authoritative model the dragging player can't have
     * optimistically diverged, but multiplayer spectators may have applied a stale pose from a
     * concurrent path, and the rebroadcast lets the sync layer reconcile them.
     */
    private void moveFreeNode(ServerWorld world, CircuitNode node, double x, double y) {
        if (!node.getComponents().isEmpty()) {
            level.notifyElementChanged(node);
            return;
        }
        PositionInfo p = node.getInfo(AllElementInfos.POSITION);
        if (p == null) {
            p = new PositionInfo();
            node.setInfo(AllElementInfos.POSITION, p);
            p.set(x, y);
            level.notifyElementChanged(node);
            return;
        }
        if (p.isFixed()) {
            level.notifyElementChanged(node);
            return;
        }
        double dx = x - p.getX();
        double dy = y - p.getY();
        CombineCascadeEngine.tryTranslateFreeNode(world, node, dx, dy);
    }
}
