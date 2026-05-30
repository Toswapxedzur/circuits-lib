package com.minecart.logic.physics;

import com.minecart.logic.CircuitElement;

import java.util.Objects;
import java.util.UUID;

/**
 * A single drag gesture's desired pose for one aggregator flush. The {@code (targetX, targetY)} is the world-
 * space position the drag wants the {@code target} element's body centre to land at — typically
 * computed client-side as {@code initialBodyCentre + (currentCursor - initialCursor)} and shipped
 * inside a {@code MoveElementPayload} stream.
 *
 * <h2>Identity</h2>
 * The {@code gestureId} uniquely identifies the gesture across the network: every payload from
 * one drag session carries the same {@code gestureId} (the client minted it at drag start). The
 * drag aggregator uses it to:
 * <ul>
 *   <li>Coalesce multiple payloads in one flush into a single "latest target" pose
 *       (streaming updates collapse to the most recent cursor position).</li>
 *   <li>Detect contention: two distinct {@code gestureId}s targeting the same element in one flush
 *       means two players are fighting over it, which triggers the "immobilise the contended
 *       element" policy. See {@link CircuitPhysicsAdapter#applyDragBatch}.</li>
 * </ul>
 *
 * <h2>Why a value type</h2>
 * Records would be lighter, but a class with explicit getters keeps the type ergonomic for
 * future fields (rotation target angle, compliance overrides, etc.) without breaking callers.
 */
public final class DragGesture {

    private final UUID gestureId;
    private final CircuitElement target;
    private final double targetX;
    private final double targetY;
    private final boolean hasAnchorLocal;
    private final double anchorLocalX;
    private final double anchorLocalY;

    public DragGesture(UUID gestureId, CircuitElement target, double targetX, double targetY) {
        this(gestureId, target, targetX, targetY, false, 0.0, 0.0);
    }

    public DragGesture(UUID gestureId, CircuitElement target, double targetX, double targetY,
                       double anchorLocalX, double anchorLocalY) {
        this(gestureId, target, targetX, targetY, true, anchorLocalX, anchorLocalY);
    }

    private DragGesture(UUID gestureId, CircuitElement target, double targetX, double targetY,
                        boolean hasAnchorLocal, double anchorLocalX, double anchorLocalY) {
        this.gestureId = Objects.requireNonNull(gestureId, "gestureId");
        this.target = Objects.requireNonNull(target, "target");
        this.targetX = targetX;
        this.targetY = targetY;
        this.hasAnchorLocal = hasAnchorLocal;
        this.anchorLocalX = anchorLocalX;
        this.anchorLocalY = anchorLocalY;
    }

    public UUID gestureId() {
        return gestureId;
    }

    public CircuitElement target() {
        return target;
    }

    public double targetX() {
        return targetX;
    }

    public double targetY() {
        return targetY;
    }

    public boolean hasAnchorLocal() {
        return hasAnchorLocal;
    }

    public double anchorLocalX() {
        return anchorLocalX;
    }

    public double anchorLocalY() {
        return anchorLocalY;
    }

    @Override
    public String toString() {
        return "DragGesture[gestureId=" + gestureId
                + " target=" + target.getId()
                + " -> (" + targetX + ", " + targetY + ")]";
    }
}
