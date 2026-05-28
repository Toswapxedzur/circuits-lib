package com.minecart.server.handler;

import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.logic.cascade.CombineCascadeEngine;
import com.minecart.logic.cascade.CombineCascadePlan;
import com.minecart.logic.cascade.DragLeaseRegistry;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.CombineCascadePayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-side handler for {@link CombineCascadePayload}. Walks each merge request through
 * {@link CombineCascadeEngine#plan} then {@link CombineCascadeEngine#apply} on the server tick
 * thread. A failing plan (lock conflict, type mismatch, unknown node, etc.) is silently dropped —
 * the client mirror will catch up on the next state sync. Eventually
 * (Phase 2c) this will also dispatch a {@code DragReplyPayload} so the client can roll back its
 * optimistic prediction.
 *
 * <p>The handler intentionally treats each pair as an independent cascade: the payload is a batch
 * for protocol economy, not a transaction across pairs. If pair 1 succeeds and pair 2 fails, pair
 * 1 stays committed. Single-pair payloads — the only kind the editor sends today — make this
 * distinction moot, and a multi-pair atomic mode can be added later if a higher-level editor flow
 * needs it.
 */
public final class CombineCascadeHandler implements PayloadHandler<CombineCascadePayload> {

    private static final Logger log = LoggerFactory.getLogger(CombineCascadeHandler.class);

    private final ServerLevel level;

    public CombineCascadeHandler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void handle(CombineCascadePayload payload) {
        level.submit(() -> apply(payload));
    }

    private void apply(CombineCascadePayload payload) {
        World world = level.findWorld(payload.getWorldId());
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        DragLeaseRegistry leases = level.getDragLeases();
        UUID gestureId = payload.getGestureId();
        for (CombineCascadePayload.CombinePair pair : payload.getPairs()) {
            CircuitNode survivor = findNode(serverWorld, pair.getSurvivorId());
            CircuitNode absorbed = findNode(serverWorld, pair.getAbsorbedId());
            if (survivor == null || absorbed == null) {
                log.debug("combine-cascade: unknown node id, skipping pair {} / {}",
                        pair.getSurvivorId(), pair.getAbsorbedId());
                continue;
            }
            // Lease check BEFORE planning: if any element involved (the two nodes or any component
            // that owns either node — those components are the ones the cascade may translate) is
            // leased to a DIFFERENT gesture, refuse without touching the world. Skipping the plan
            // is a small efficiency win (planning allocates) but the real reason is that planning
            // shouldn't observe state another client owns.
            List<UUID> touched = touchedIdsFor(survivor, absorbed);
            if (!leases.canOperateOn(gestureId, touched)) {
                log.debug("combine-cascade: refused pair ({}, {}) — touched element leased elsewhere",
                        survivor.getId(), absorbed.getId());
                continue;
            }
            CombineCascadePlan plan = CombineCascadeEngine.plan(serverWorld, survivor, absorbed);
            if (!plan.isSuccess()) {
                log.debug("combine-cascade: refused pair ({}, {}) reason={}",
                        survivor.getId(), absorbed.getId(), plan.getReason());
                continue;
            }
            boolean applied = CombineCascadeEngine.apply(serverWorld, plan.getOps());
            if (!applied) {
                log.warn("combine-cascade: plan applied=false for pair ({}, {})",
                        survivor.getId(), absorbed.getId());
            }
        }
    }

    /**
     * Builds the list of ids the lease registry should check before allowing the cascade. Includes
     * both nodes and every component that owns either node. The cascade engine may translate one
     * of those components (cross-component port-on-port case), so a lease held by another gesture
     * on the component means we'd race them mid-drag.
     */
    private static List<UUID> touchedIdsFor(CircuitNode survivor, CircuitNode absorbed) {
        List<UUID> ids = new ArrayList<>(6);
        ids.add(survivor.getId());
        ids.add(absorbed.getId());
        for (CircuitComponent c : survivor.getComponents()) {
            if (c != null) ids.add(c.getId());
        }
        for (CircuitComponent c : absorbed.getComponents()) {
            if (c != null) ids.add(c.getId());
        }
        return ids;
    }

    private static CircuitNode findNode(World world, UUID id) {
        if (id == null) {
            return null;
        }
        for (Circuit circuit : world.getCircuits()) {
            CircuitNode n = circuit.findNode(id);
            if (n != null) {
                return n;
            }
        }
        return null;
    }
}
