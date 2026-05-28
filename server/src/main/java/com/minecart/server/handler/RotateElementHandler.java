package com.minecart.server.handler;

import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.logic.cascade.CombineCascadeEngine;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.RotateElementPayload;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.LockInfo;
import com.minecart.variant.info.LockMode;

import java.util.Objects;
import java.util.UUID;

/**
 * Server-side handler for {@link RotateElementPayload}. Used by the gesture surface (Phase 3c:
 * scroll-rotate and 1-second right-press pivot-rotate). Two-step apply:
 *
 * <ol>
 *   <li><b>Update strictLock</b> to {@link LockMode#ROTATION_FREE} pivoted at the requested
 *       {@code (pivotX, pivotY)}. Lazily creates a {@link LockInfo} on the element when one isn't
 *       attached yet. Refuses when an existing {@link LockInfo} has
 *       {@code mutableByPlayer=false} — the strict lock is intentionally permanent and gestures
 *       shouldn't bypass that. Also refuses when the existing mode is {@link LockMode#LOCKED}.</li>
 *   <li><b>Apply rotation</b> via {@link CombineCascadeEngine#tryRotateComponent}. The pivot-
 *       coincidence rule in the engine accepts the request because step 1 just made the strict
 *       pivot equal the requested pivot.</li>
 * </ol>
 *
 * <p>Edges are out of scope this phase — they need an endpoint-by-endpoint cascade analogous to
 * the component case (translate parent components or update free endpoint positions) and a
 * dedicated edge-rotate primitive. TODO(phase-3c-edges).
 */
public final class RotateElementHandler implements PayloadHandler<RotateElementPayload> {

    private final ServerLevel level;

    public RotateElementHandler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void handle(RotateElementPayload payload) {
        level.submit(() -> apply(payload));
    }

    private void apply(RotateElementPayload payload) {
        World world = level.findWorld(payload.getWorldId());
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        CircuitElement element = findElement(world, payload.getElementId());
        if (!(element instanceof CircuitComponent component)) {
            // Phase 3c scope: rotation only for components. Drop silently; sync will re-assert.
            return;
        }

        // Update strict lock first so the engine's pivot-coincidence check accepts the rotation.
        LockInfo lock = component.getInfo(AllElementInfos.LOCK);
        if (lock == null) {
            lock = new LockInfo();
            component.setInfo(AllElementInfos.LOCK, lock);
        }
        if (!lock.isMutableByPlayer()) {
            // Permanent strict lock — gesture refuses to override it.
            return;
        }
        if (lock.getMode() == LockMode.LOCKED) {
            // User explicitly locked the element; gestures don't break that.
            return;
        }
        // Promote FREE → ROTATION_FREE on first gesture use; leave POSITION_FREE / ROTATION_FREE
        // alone (they already permit rotation; rewriting POSITION_FREE → ROTATION_FREE would
        // silently lose translation freedom). Always update the pivot so the requested rotation
        // can land.
        if (lock.getMode() == LockMode.FREE) {
            lock.setMode(LockMode.ROTATION_FREE);
        }
        lock.setPivot(payload.getPivotX(), payload.getPivotY());

        // Engine call. Notifies via Level.notifyElementChanged on success so the standard sync
        // replicates the rotation back to client mirrors.
        CombineCascadeEngine.tryRotateComponent(
                serverWorld, component,
                payload.getPivotX(), payload.getPivotY(),
                payload.getDeltaRadians());
        // Also notify component itself so the LockInfo update propagates even if the rotation
        // delta was zero (defensive — gestures usually send non-zero deltas).
        level.notifyElementChanged(component);
    }

    private static CircuitElement findElement(World world, UUID id) {
        if (id == null) return null;
        for (Circuit c : world.getCircuits()) {
            CircuitElement el = c.findElement(id);
            if (el != null) return el;
        }
        return null;
    }
}
