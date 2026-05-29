package com.minecart.logic.physics;

import com.minecart.foundation.World;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.LockMode;
import com.minecart.variant.info.LockState;
import com.minecart.variant.info.PositionInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-tick aggregator for streaming drag gestures. Replaces the old explicit drag-lease system:
 * incoming {@code MoveElementPayload} (and analogous translation payloads) are buffered into this
 * aggregator instead of mutating the world inline; once per tick {@link ServerLevel#tick()} calls
 * {@link #flush(ServerLevel)} to resolve everything in a single unified physics solve.
 *
 * <h2>Lifecycle per tick</h2>
 * <ol>
 *   <li>Client streams Move payloads at ~20 Hz. Each handler does
 *       {@code level.submit(() -> level.getDragAggregator().enqueue(gesture))}.</li>
 *   <li>{@link ServerLevel#tick} drains the action queue → all this-tick gestures land in
 *       {@link #buffered}.</li>
 *   <li>{@link #flush} groups gestures by world, runs one
 *       {@link CircuitPhysicsAdapter#applyDragBatch} call per world, then clears the buffer.</li>
 *   <li>Per-tick refusal broadcasts: every gesture's target element gets a
 *       {@code notifyElementChanged}, so clients whose optimistic prediction diverged from the
 *       authoritative pose see a correction. Locked elements / contended elements naturally do
 *       not move under {@code applyDragBatch}, so the broadcast carries the unchanged pose.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * {@link #enqueue} is called from the tick thread (via {@code level.submit}); not thread-safe by
 * itself, but the queueing pattern ensures single-thread access. {@link #flush} runs on the same
 * thread.
 */
public final class DragAggregator {

    private static final Logger log = LoggerFactory.getLogger(DragAggregator.class);

    /**
     * Per-world buffer. Outer key is the world id; inner list is the gestures received against
     * that world this tick. Multiple gestures with the same {@code (target, gestureId)} pair
     * accumulate in insertion order — {@link CircuitPhysicsAdapter#applyDragBatch} coalesces them
     * to the latest target per pair.
     */
    private final Map<UUID, List<DragGesture>> buffered = new LinkedHashMap<>();

    /**
     * Buffers a single gesture for resolution on the next {@link #flush}. The {@code worldId}
     * routes the gesture to the correct world's solve; gestures with no matching world at flush
     * time are silently dropped (the world was deleted between enqueue and flush).
     */
    public void enqueue(UUID worldId, DragGesture gesture) {
        if (worldId == null || gesture == null) {
            return;
        }
        buffered.computeIfAbsent(worldId, k -> new ArrayList<>()).add(gesture);
    }

    /**
     * Whether any gestures are buffered. Exposed for diagnostics / tests; the tick path always
     * calls {@link #flush} unconditionally so an empty buffer is a no-op solve.
     */
    public boolean isEmpty() {
        return buffered.isEmpty();
    }

    /**
     * Resolves all buffered gestures against {@code level}'s worlds and clears the buffer. Each
     * world's gestures are passed to {@link CircuitPhysicsAdapter#applyDragBatch} for a single
     * unified spring solve.
     *
     * <p>After the solve, every gesture's target element is re-notified to clients regardless of
     * whether its pose actually changed — this is the refusal-broadcast: clients that optimistic-
     * predicted a move on a locked / contended element will receive the unchanged authoritative
     * pose and snap back. Clients whose prediction matched will see a no-op delta and the existing
     * sync infrastructure deduplicates it.
     */
    public void flush(ServerLevel level) {
        if (level == null || buffered.isEmpty()) {
            buffered.clear();
            return;
        }
        // Snapshot the buffer, then clear — the solve and notify steps may queue follow-up
        // actions (notifyElementChanged → sync delta) that we don't want re-entering the buffer.
        Map<UUID, List<DragGesture>> snapshot = new LinkedHashMap<>(buffered);
        buffered.clear();

        for (Map.Entry<UUID, List<DragGesture>> e : snapshot.entrySet()) {
            UUID worldId = e.getKey();
            List<DragGesture> gestures = e.getValue();
            World world = level.findWorld(worldId);
            if (!(world instanceof ServerWorld serverWorld)) {
                if (log.isDebugEnabled()) {
                    log.debug("drag-aggregator: dropping {} gestures for unknown world {}",
                            gestures.size(), worldId);
                }
                continue;
            }
            // Strict-lock preflight: drop gestures whose target carries a strict LOCKED state.
            // The physics layer would already refuse to move a locked body, but explicitly
            // filtering here avoids polluting the solve and produces a cleaner refusal broadcast.
            List<DragGesture> accepted = new ArrayList<>(gestures.size());
            for (DragGesture g : gestures) {
                if (isStrictlyLocked(g.target(), g)) {
                    if (log.isDebugEnabled()) {
                        log.debug("drag-aggregator: refused gesture {} on locked target {}",
                                g.gestureId(), g.target().getId());
                    }
                    continue;
                }
                accepted.add(g);
            }
            if (!accepted.isEmpty()) {
                CircuitPhysicsAdapter.applyDragBatch(serverWorld, accepted);
            }
            // Broadcast authoritative pose for every gesture's target, accepted or not. Locked
            // elements that we refused never moved, so the broadcast snaps the client back.
            // Successful drags' targets are also re-broadcast in case the client's optimistic
            // prediction diverged (e.g. multiple players contending → element immobilised).
            for (DragGesture g : gestures) {
                notifyTarget(level, g.target());
            }
        }
    }

    /**
     * Returns {@code true} when {@code element} carries a strict {@code LOCKED} or movement-
     * blocking lock state that should refuse drag translations. {@code ROTATION_FREE} elements
     * (locked translation, free rotation) reject translations; {@code POSITION_FREE} elements
     * still translate. {@code LOCKED} and unset state (treated as fully locked when set by the
     * player) refuse outright.
     *
     * <p>The check uses the same {@code effectiveLockState} the physics adapter consults, but
     * specialised here to "is this translation legal?". Rotation gestures don't go through the
     * aggregator (they're discrete one-shots) and are checked by the rotate handler directly.
     */
    private static boolean isStrictlyLocked(CircuitElement element, DragGesture gesture) {
        if (element == null) return true;
        if (element instanceof CircuitComponent comp) {
            LockState eff = comp.effectiveLockState(1e-6);
            LockMode mode = eff.mode();
            return mode == LockMode.LOCKED || mode == LockMode.ROTATION_FREE;
        }
        if (element instanceof CircuitNode node) {
            if (!node.getComponents().isEmpty()) {
                // Port node — moving via a drag gesture shouldn't reach here (the drag controller
                // routes through the parent component); refuse defensively.
                return true;
            }
            PositionInfo p = node.getInfo(AllElementInfos.POSITION);
            return p != null && p.isFixed();
        }
        return true;
    }

    private static void notifyTarget(ServerLevel level, CircuitElement element) {
        if (element == null) return;
        level.notifyElementChanged(element);
    }
}
