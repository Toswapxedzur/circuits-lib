package com.minecart.server.handler;

import com.minecart.foundation.World;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.logic.cascade.CombineCascadeEngine;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.RotateElementPayload;

import java.util.Objects;

/**
 * Server-side handler for {@link RotateElementPayload}. Used by the gesture surface (Phase 3c:
 * scroll-rotate and 1-second right-press pivot-rotate).
 *
 * <p>Delegates the rotation entirely to {@link CombineCascadeEngine#tryRotateComponent} which
 * already consults the component's effective {@link com.minecart.variant.info.LockState} (the AND
 * of strict {@link com.minecart.variant.info.LockInfo} and the soft-lock derivation) and refuses
 * silently if rotation is forbidden. The handler itself does NOT mutate the component's strict
 * {@link com.minecart.variant.info.LockInfo} — earlier revisions did, attempting to coax the
 * engine's pivot-coincidence check into accepting the gesture by promoting FREE → PIVOTED
 * and rewriting the stored pivot, but the side-effect corrupted the player's authored lock state
 * even when the rotation was ultimately refused. With the side-effect removed, an unmovable
 * lock stays unmovable, and a mutable lock keeps the {@code (mode, pivot)} the player authored.
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
        // The dispatcher already marshals onto the tick thread; apply directly (no second submit hop).
        apply(payload);
    }

    private void apply(RotateElementPayload payload) {
        World world = level.findWorld(payload.getWorldId());
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        CircuitElement element = ElementLookup.findElement(world, payload.getElementId());
        if (!(element instanceof CircuitComponent component)) {
            // Phase 3c scope: rotation only for components. Drop silently; sync will re-assert.
            return;
        }
        // Pure delegation: the engine's effectiveLockState check is the single source of truth
        // for whether the rotation is permitted.
        CombineCascadeEngine.tryRotateComponent(
                serverWorld, component,
                payload.getPivotX(), payload.getPivotY(),
                payload.getDeltaRadians());
    }
}
