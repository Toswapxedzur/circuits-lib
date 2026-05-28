package com.minecart.logic.cascade;

import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerWorld;

/**
 * Terminal op of a combine cascade: removes the absorbed free node via
 * {@link ServerWorld#destroy(CircuitNode)} once every edge that used to reference it has already
 * been re-routed onto the survivor.
 *
 * <h2>Why destroy is unidirectional</h2>
 * Properly undoing a destroy means restoring the node's UUID, its electrical info maps, its
 * circuit membership, and rebuilding every incident edge. None of those are recoverable from the
 * info this op carries — we'd need a full pre-destroy snapshot of every related element. So we
 * place destroy LAST in the plan, after every earlier op has succeeded. If destroy itself returns
 * {@code false} the engine treats it as a real failure and unwinds the prior ops; if destroy
 * succeeds the cascade is committed and no undo will ever run. The {@link #undo} implementation is
 * therefore a no-op with a guard: it should only be reachable on a bug.
 */
public final class DestroyNodeOp implements CascadeOp {

    private final CircuitNode node;

    public DestroyNodeOp(CircuitNode node) {
        this.node = node;
    }

    @Override
    public boolean apply(ServerWorld world) {
        if (node == null) {
            return false;
        }
        return world.destroy(node);
    }

    @Override
    public void undo(ServerWorld world) {
        // Intentional no-op. See class javadoc — destroy is terminal and shouldn't be in the undo
        // stack. If we get here it means the engine pushed this op then encountered a failure
        // before any later op ran, which is impossible because destroy is always last; or an
        // unrelated bug. Logging is left to the engine.
    }

    public CircuitNode getNode() {
        return node;
    }
}
