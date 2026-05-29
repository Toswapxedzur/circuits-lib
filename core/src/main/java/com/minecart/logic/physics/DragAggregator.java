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
 * Aggregator for streaming drag gestures. Replaces the old explicit drag-lease system:
 * incoming {@code MoveElementPayload} samples are buffered into this aggregator instead of
 * mutating the world inline; {@link #flush(ServerLevel)} resolves the buffered samples in a
 * single unified physics solve. In production, {@link com.minecart.server.network.LevelPumps}
 * flushes this at a higher cadence than the main logic tick so server-authoritative drag feels
 * responsive without client-side pose prediction.
 *
 * <h2>Lifecycle per tick</h2>
 * <ol>
 *   <li>Client streams Move payloads containing cursor target poses. Each handler does
 *       {@code level.submit(() -> level.getDragAggregator().enqueue(gesture))}.</li>
 *   <li>The level action queue is drained by the main tick and by the high-frequency drag pump,
 *       so pending samples land in {@link #buffered} quickly.</li>
 *   <li>{@link #flush} groups gestures by world, runs one
 *       {@link CircuitPhysicsAdapter#applyDragBatch} call per world, then clears the buffer.</li>
 *   <li>Every gesture target gets {@code notifyElementChanged}; accepted drags broadcast fresh
 *       authoritative poses, while locked / contended drags reassert the unchanged pose.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * {@link #enqueue} is called from the level worker thread (via {@code level.submit}); not
 * thread-safe by itself, but the queueing pattern ensures single-thread access. {@link #flush}
 * runs on the same worker thread.
 */
public final class DragAggregator {

    private static final Logger log = LoggerFactory.getLogger(DragAggregator.class);

    /**
     * Per-world buffer. Outer key is the world id; inner list is the gestures received against
     * that world since the previous flush. Multiple gestures with the same {@code (target, gestureId)} pair
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
     * Whether any gestures are buffered. Exposed for diagnostics / tests; pump paths call
     * {@link #flush} unconditionally so an empty buffer is a no-op solve.
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
     * whether its pose actually changed. Accepted drags broadcast the new authoritative pose;
     * locked / contended drags broadcast the unchanged pose so all clients keep the same mirror.
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
            // elements that we refused never moved, so this reasserts the unchanged pose; accepted
            // targets are also rebroadcast so clients receive the latest server-solved pose.
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
