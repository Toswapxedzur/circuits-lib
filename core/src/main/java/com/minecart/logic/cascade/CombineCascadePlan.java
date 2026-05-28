package com.minecart.logic.cascade;

import java.util.Collections;
import java.util.List;

/**
 * Output of {@link CombineCascadeEngine#plan}: either an ordered op list ready for
 * {@link CombineCascadeEngine#apply} or a reason the merge can't proceed.
 *
 * <p>The engine returns this as a separate value (rather than mutating world state) so callers
 * (network handlers, tests, future UI previews) can inspect the plan before committing — e.g. a
 * client-side dry-run that wants to confirm "yes, this drag would translate component X by (dx,
 * dy)" before sending the actual op payload.
 *
 * <p>Failure modes share one structured enum so handlers can distinguish "lock conflict" from "no
 * such node" without parsing free-text strings — they often need to reply to the client with a
 * specific roll-back signal.
 */
public final class CombineCascadePlan {

    /** Why a plan was refused. {@link #OK} is the only non-failure value. */
    public enum Reason {
        OK,
        /** Either or both nodes were {@code null} or didn't belong to the target world. */
        INVALID_INPUT,
        /** {@code canCombine} on one of the nodes vetoed the merge (e.g. non-port internal). */
        CANNOT_COMBINE,
        /** Port type ids don't match for a port-on-port slot swap. */
        TYPE_MISMATCH,
        /** Both components touched by a cross-component cascade refuse translation (locked). */
        LOCK_CONFLICT,
        /** A node has multiple component owners and the engine can't safely move it (Phase 2c+). */
        SHARED_NODE_UNSUPPORTED,
        /** Internal engine assertion failed; safe fallback to telling the user "try again". */
        INTERNAL
    }

    private final Reason reason;
    private final List<CascadeOp> ops;

    private CombineCascadePlan(Reason reason, List<CascadeOp> ops) {
        this.reason = reason;
        this.ops = ops;
    }

    public static CombineCascadePlan success(List<CascadeOp> ops) {
        return new CombineCascadePlan(Reason.OK, ops != null ? List.copyOf(ops) : List.of());
    }

    public static CombineCascadePlan failure(Reason reason) {
        return new CombineCascadePlan(
                reason != null ? reason : Reason.INTERNAL,
                Collections.emptyList());
    }

    public boolean isSuccess() {
        return reason == Reason.OK;
    }

    public Reason getReason() {
        return reason;
    }

    public List<CascadeOp> getOps() {
        return ops;
    }
}
