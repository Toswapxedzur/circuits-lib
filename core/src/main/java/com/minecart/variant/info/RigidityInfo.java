package com.minecart.variant.info;

import com.minecart.serialization.tag.CompoundTag;
import com.minecart.variant.ElementInfo;

/**
 * Player-authored "rigid" flag on a {@link com.minecart.logic.CircuitEdge}. When set, the edge
 * participates in the physics solver's kinematic constraint graph as a fixed-length distance
 * connector: dragging one of its endpoint bodies pulls the other endpoint along the edge's
 * current length, propagating motion across the connected sub-graph. When unset (the default),
 * the edge is purely visual — its endpoints move independently and the edge line just stretches
 * to follow them.
 *
 * <h2>Why opt-in</h2>
 * Most wires in an editor session are short visual connections that the user doesn't want to
 * "drag around" with: marking everything rigid would make routine layout edits cascade
 * unexpectedly across the whole circuit. Defaulting to flexible keeps the existing single-element
 * drag behaviour while letting power-users opt selected wires into rigid coupling. The flag is
 * authored from the edge's panel fragment so existing components don't need migration.
 *
 * <h2>What rigid does NOT change</h2>
 * <ul>
 *   <li>Electrical semantics. The edge's branch / device equation contribution is independent of
 *       this flag — wiring topology and current flow are decided by graph membership, not by
 *       rigidity.</li>
 *   <li>The strict lock state ({@link LockInfo}). Rigidity is a kinematic-graph hint; locking is
 *       a motion permission. The two compose: a rigid edge with a LOCKED strict lock still
 *       refuses topological changes (handler-level guard) but participates as a constraint.</li>
 *   <li>Multi-owner shared port nodes. Those produce pin joints between owning components
 *       regardless of any edge's rigid flag, because the shared anchor is inherent to the topology,
 *       not optional.</li>
 * </ul>
 */
public class RigidityInfo implements ElementInfo {

    private static final String TAG_RIGID = "rigid";

    private boolean rigid;

    public RigidityInfo() {
        this.rigid = false;
    }

    public RigidityInfo(boolean rigid) {
        this.rigid = rigid;
    }

    public boolean isRigid() {
        return rigid;
    }

    /**
     * Sets the rigid flag.
     *
     * @return {@code true} if the state actually changed.
     */
    public boolean setRigid(boolean rigid) {
        if (this.rigid == rigid) {
            return false;
        }
        this.rigid = rigid;
        return true;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putBoolean(TAG_RIGID, rigid);
    }

    @Override
    public void load(CompoundTag tag) {
        this.rigid = tag.get(TAG_RIGID) != null && tag.getBoolean(TAG_RIGID);
    }
}
