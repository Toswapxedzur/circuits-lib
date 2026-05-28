package com.minecart.logic.cascade;

import com.minecart.logic.ServerWorld;

/**
 * A single reversible operation in a combine-cascade plan. The cascade engine builds a list of ops
 * in dependency order (e.g. translate the absorbed component → swap its port slot → re-route edges
 * → destroy the orphan node), then runs them through {@link CombineCascadeEngine#apply}.
 *
 * <h2>Atomicity contract</h2>
 * <ul>
 *   <li>{@link #apply(ServerWorld)} must return {@code true} on success or {@code false} on failure.
 *       A failure means "the world state is unchanged for this op" — the engine relies on this to
 *       avoid rolling back a no-op.</li>
 *   <li>{@link #undo(ServerWorld)} reverses a successful {@link #apply}. Called by the engine only
 *       on ops that previously returned {@code true} from {@link #apply}, walking the stack in
 *       reverse order. Undo is best-effort: it must do the right thing on a clean apply→undo pair,
 *       but the engine never undoes the same op twice, so it doesn't have to be idempotent.</li>
 *   <li>Ops should NOT call {@link com.minecart.logic.Level#notifyElementChanged} or post other
 *       broadcast events themselves — the engine emits a single batched broadcast after the whole
 *       plan succeeds. Rolling back partial events to the client mirror would be more trouble than
 *       the atomicity is worth.</li>
 * </ul>
 *
 * <h2>Preflight</h2>
 * Lock-aware geometry checks, type compatibility, and "is anything leased to another player" are
 * decided <em>at plan time</em>, before any op is added to the plan. Once apply runs we expect every
 * op to succeed; the rollback path exists as defence in depth for race conditions (e.g. another
 * concurrent change between plan and apply on the server side).
 */
public interface CascadeOp {

    boolean apply(ServerWorld world);

    void undo(ServerWorld world);
}
