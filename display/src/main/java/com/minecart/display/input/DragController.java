package com.minecart.display.input;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.minecart.client.network.ClientConnection;
import com.minecart.display.editor.Editor;
import com.minecart.display.editor.EditorTool;
import com.minecart.display.render.WorldStage;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.protocol.payload.client.CombineCascadePayload;
import com.minecart.protocol.payload.client.DeleteElementPayload;
import com.minecart.protocol.payload.client.DragBeginPayload;
import com.minecart.protocol.payload.client.DragEndPayload;
import com.minecart.protocol.payload.client.MoveElementPayload;
import com.minecart.protocol.payload.client.RotateElementPayload;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.PositionInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cursor-driven hover + drag + trashcan input adapter. Plugs into the screen's
 * {@link com.badlogic.gdx.InputMultiplexer} between the UI stage and the camera/editor.
 *
 * <h2>Hover</h2>
 * Every {@link #mouseMoved} reports the topmost {@link CircuitElement} under the cursor to
 * {@link WorldStage#setHoveredElementId(UUID)} so the renderer can apply a yellow tint. Returns {@code false}
 * so downstream processors still see the move.
 *
 * <h2>Drag</h2>
 * Left {@link #touchDown} over a draggable element (free {@link CircuitNode} or {@link CircuitComponent})
 * captures it <em>only while the editor tool is Idle</em>, so a placement tool always wins click priority.
 * During {@link #touchDragged} the element's local {@link PositionInfo} is rewritten so the actor visually
 * follows the cursor instantly — the authoritative server update lands later through the normal sync
 * pipeline. On {@link #touchUp} a {@link MoveElementPayload} (or {@link DeleteElementPayload} when released
 * over the trashcan rectangle) is sent.
 *
 * <h2>Trashcan</h2>
 * The trashcan rectangle is supplied as a screen-space {@link TrashBoundsSupplier} so the screen layout can
 * compute it from the UI stage every frame. The same supplier returns {@code null} when no rectangle should
 * be considered active (e.g. UI not yet built), in which case the trash check is skipped.
 */
public class DragController extends InputAdapter {

    /** Screen-space bounds of the trashcan, or {@code null} if unknown / not active. */
    @FunctionalInterface
    public interface TrashBoundsSupplier {
        /** Returns {@code [x, y, w, h]} in libGDX screen coordinates (y up), or {@code null}. */
        float[] get();
    }

    private final WorldStage stage;
    private final ClientConnection connection;
    private final Editor editor;
    private final Supplier<UUID> selectedWorldId;
    private final TrashBoundsSupplier trashBounds;
    /** Notified with {@code true} on drag start and {@code false} on drag end so the UI can show the can. */
    private final java.util.function.Consumer<Boolean> onDragStateChanged;

    /** What kind of element is currently being dragged; controls drag-update / touchUp branching. */
    private enum DragKind { NODE, COMPONENT, EDGE }

    private UUID draggingId;
    /**
     * The element the user actually clicked, which may differ from {@link #draggingId} when the
     * click landed on a port node owned by a component: {@code draggingId} redirects to the parent
     * component (so the drag translates the whole body), while {@code clickedId} stays on the node
     * so a no-movement click fires {@link #onElementClicked} for the node itself. This is what lets
     * a user pick which info panel opens — node vs. component — based on what they're aiming at,
     * without giving up the convenience of dragging the body by its ports.
     */
    private UUID clickedId;
    private DragKind dragKind;
    /** World-space cursor position at drag start. */
    private float dragStartWorldX;
    private float dragStartWorldY;
    /** Element's original position at drag start (component centre or free node position). */
    private double originX;
    private double originY;
    /** Original positions of the component's internal nodes captured at drag start. */
    private final java.util.Map<UUID, double[]> originalNodePositions = new java.util.HashMap<>();
    /**
     * For an edge drag: ids of the "movables" (free nodes or component centres) that need to be
     * translated by the same delta so the wire's two endpoints keep their relative offset. Captured at
     * drag start to avoid recomputing per touchDragged. Origins of those movables live in
     * {@link #edgeMovableOrigins}; whether each id is a component vs. a free node lives in
     * {@link #edgeMovableIsComponent}. Edge endpoints that are an intrinsic non-port internal node are
     * skipped — the edge's drag would orphan such a port from its parent component, which is exactly
     * what {@code PositionInfo.isFixed()} is meant to prevent.
     */
    private final java.util.LinkedHashSet<UUID> edgeMovableIds = new java.util.LinkedHashSet<>();
    private final java.util.Map<UUID, double[]> edgeMovableOrigins = new java.util.HashMap<>();
    private final java.util.Map<UUID, Boolean> edgeMovableIsComponent = new java.util.HashMap<>();
    /** Set after a drag actually moves so a stationary click doesn't generate a no-op MoveElement. */
    private boolean movedSinceDown;
    /**
     * Gesture id allocated at drag start and threaded through every payload the drag emits
     * (DragBegin / DragEnd, and as the {@code gestureId} on any CombineCascadePayload fired from
     * touchUp). Multi-client correlation handle for the server's
     * {@link com.minecart.logic.cascade.DragLeaseRegistry}: while this id holds leases, other
     * clients trying to drag the same elements are refused. {@code null} when no drag is active.
     */
    private UUID dragGestureId;
    /** The world the active drag belongs to — captured at begin so end can route to the same world. */
    private UUID dragWorldId;
    /** Element ids registered with the active drag's lease, kept so end can release the same set. */
    private final java.util.List<UUID> leasedElementIds = new java.util.ArrayList<>();
    /**
     * When dragging a free node, the id of the other node currently under the cursor (if any) — used
     * to render a "combine target" hint and to dispatch the combine payload sequence on touchUp. Reset
     * each frame in {@link #touchDragged}.
     */
    private UUID combineTargetId;

    // --- Phase 3c gesture state ---------------------------------------------------------------

    /** Per-notch rotation in radians for the scroll-rotate gesture (7.5°). */
    private static final double SCROLL_ROTATION_STEP = Math.PI / 24.0;
    /** How long the right mouse button must be held over an element before pivot-rotate activates. */
    private static final long RIGHT_PRESS_ACTIVATION_MS = 1000L;

    /** Element targeted by the active right-press gesture (component, or null when not gesturing). */
    private UUID rightGestureElementId;
    /** Millis at which the current right-press began, or 0 when no right-press is being tracked. */
    private long rightPressStartMs;
    /** Once the 1-second threshold has been crossed, becomes {@code true} and cursor motion rotates. */
    private boolean rightPressActive;
    /** Previous cursor world position used to derive per-frame angular delta during pivot-rotate. */
    private float rightPressPrevWorldX;
    private float rightPressPrevWorldY;

    /**
     * Invoked on a stationary click on a draggable element (touchDown + touchUp without any
     * movement in between). The handler typically opens the per-element info panel; the
     * controller itself doesn't know about that subsystem, just emits the signal.
     */
    private final java.util.function.BiConsumer<UUID, UUID> onElementClicked;
    /**
     * Returns the id of the element whose info panel is currently open, or {@code null} when no
     * panel is open. The drag controller polls this every {@code touchDown} to refuse starting a
     * drag on the actively-edited element — that's the client-side enforcement of "an element is
     * forcibly locked while editing". Multi-client enforcement still goes through the server's
     * drag-lease registry; this supplier just keeps the local user from racing themselves.
     */
    private final Supplier<UUID> editingElementId;

    public DragController(WorldStage stage,
                          ClientConnection connection,
                          Editor editor,
                          Supplier<UUID> selectedWorldId,
                          TrashBoundsSupplier trashBounds,
                          java.util.function.Consumer<Boolean> onDragStateChanged) {
        this(stage, connection, editor, selectedWorldId, trashBounds, onDragStateChanged, null);
    }

    public DragController(WorldStage stage,
                          ClientConnection connection,
                          Editor editor,
                          Supplier<UUID> selectedWorldId,
                          TrashBoundsSupplier trashBounds,
                          java.util.function.Consumer<Boolean> onDragStateChanged,
                          java.util.function.BiConsumer<UUID, UUID> onElementClicked) {
        this(stage, connection, editor, selectedWorldId, trashBounds, onDragStateChanged,
                onElementClicked, null);
    }

    public DragController(WorldStage stage,
                          ClientConnection connection,
                          Editor editor,
                          Supplier<UUID> selectedWorldId,
                          TrashBoundsSupplier trashBounds,
                          java.util.function.Consumer<Boolean> onDragStateChanged,
                          java.util.function.BiConsumer<UUID, UUID> onElementClicked,
                          Supplier<UUID> editingElementId) {
        this.stage = stage;
        this.connection = connection;
        this.editor = editor;
        this.selectedWorldId = selectedWorldId;
        this.trashBounds = trashBounds;
        this.onDragStateChanged = onDragStateChanged != null ? onDragStateChanged : v -> {};
        this.onElementClicked = onElementClicked != null ? onElementClicked : (w, e) -> {};
        this.editingElementId = editingElementId != null ? editingElementId : () -> null;
    }

    public boolean isDragging() {
        return draggingId != null;
    }

    /**
     * @return the id of the node currently under the dragged-node cursor that the drop would combine
     *         into, or {@code null} if no candidate is under the cursor or the active drag isn't a
     *         node. Useful for the renderer to tint the combine target.
     */
    public UUID getCombineTargetId() {
        return combineTargetId;
    }

    @Override
    public boolean mouseMoved(int screenX, int screenY) {
        updateHoverFromScreen(screenX, screenY);
        return false;
    }

    private void updateHoverFromScreen(int screenX, int screenY) {
        float[] w = stage.screenToWorld(screenX, screenY);
        CircuitElement el = stage.hitTestWorld(w[0], w[1]);
        // Hoverable surface mirrors the click surface: components, every node (including ports on
        // a component — they're independently selectable with node-priority hit-testing so the
        // hover hint should also follow the node), and free edges. Component-internal edges stay
        // un-hoverable because they aren't user-interactable on their own.
        if (el instanceof CircuitComponent
                || el instanceof CircuitNode
                || (el instanceof CircuitEdge edge && edge.getComponent() == null)) {
            stage.setHoveredElementId(el.getId());
        } else {
            stage.setHoveredElementId(null);
        }
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (button == Input.Buttons.RIGHT) {
            // Right-press starts the pivot-rotate gesture: hold for 1s over a component, then any
            // cursor motion rotates it around the cursor. We only claim the event when the press
            // lands on a component so right-click on empty canvas still falls through to the
            // camera's pan behaviour. The actual rotation doesn't fire until the threshold passes
            // AND the cursor moves — see {@link #touchDragged}.
            return beginRightPress(screenX, screenY);
        }
        if (button != Input.Buttons.LEFT) {
            return false;
        }
        // Refuse to claim the click while a placement tool is active so the editor keeps placement
        // ergonomics. The user can press Esc to drop the tool and then drag existing elements.
        if (!(editor.getTool() instanceof EditorTool.Idle)) {
            return false;
        }
        float[] w = stage.screenToWorld(screenX, screenY);
        CircuitElement el = stage.hitTestWorld(w[0], w[1]);
        if (el == null) {
            return false;
        }
        // Forcibly-locked-while-editing: if the click would start a drag on the element whose info
        // panel is open (directly, or on a port redirecting to its parent component), refuse the
        // drag entirely. Returning true consumes the event so the camera doesn't pan either —
        // clicking on a locked element should feel like clicking on a wall. We deliberately do
        // NOT record this as a click target (no clickedId) so touchUp doesn't try to reopen the
        // panel; the panel is already open and stays open until Save / Cancel.
        if (isEditingTarget(el)) {
            return true;
        }
        if (el instanceof CircuitComponent comp) {
            beginDrag(comp);
            clickedId = comp.getId();
            dragStartWorldX = w[0];
            dragStartWorldY = w[1];
            return true;
        }
        if (el instanceof CircuitNode node) {
            // Internal port nodes belong to their component's pose; route drag through the parent so
            // anchor offsets stay correct. The CLICK target stays on the port itself though — a
            // no-movement click on a port should open the node's info panel, not the component's.
            // Belt-and-braces: also honour PositionInfo.isFixed() so a port whose component pointer
            // somehow didn't get linked client-side (stale mirror, replication race) still resists
            // being dragged off; it can still receive a click via clickedId though.
            if (node.getComponent() != null) {
                beginDrag(node.getComponent());
                clickedId = node.getId();
            } else if (isPositionFixed(node)) {
                clickedId = node.getId();
                return true;
            } else {
                beginDragFreeNode(node);
                clickedId = node.getId();
            }
            dragStartWorldX = w[0];
            dragStartWorldY = w[1];
            return true;
        }
        if (el instanceof CircuitEdge edge) {
            // Edge dragging keeps the wire's two endpoints at a fixed offset from each other (no
            // rotation, no length change), so the drag is implemented as "translate the edge's
            // two movables by the same delta". A movable is the parent component (if the endpoint is
            // part of one — its anchor system replays the move to every internal node) or the free
            // node itself. Skip the click entirely if the edge is component-internal (its endpoints
            // are not user-relocatable on their own and pulling the parent component would be the
            // wrong UX since the user is targeting the wire, not the body).
            if (edge.getComponent() != null) {
                return true;
            }
            beginDragEdge(edge);
            clickedId = edge.getId();
            dragStartWorldX = w[0];
            dragStartWorldY = w[1];
            return true;
        }
        return false;
    }

    private static boolean isPositionFixed(CircuitNode node) {
        PositionInfo p = node.getInfo(AllElementInfos.POSITION);
        return p != null && p.isFixed();
    }

    private void beginDrag(CircuitComponent comp) {
        draggingId = comp.getId();
        dragKind = DragKind.COMPONENT;
        movedSinceDown = false;
        PositionInfo pos = comp.getInfo(AllElementInfos.POSITION);
        originX = pos != null ? pos.getX() : 0.0;
        originY = pos != null ? pos.getY() : 0.0;
        originalNodePositions.clear();
        for (CircuitNode n : comp.getNodes()) {
            PositionInfo np = n.getInfo(AllElementInfos.POSITION);
            originalNodePositions.put(n.getId(),
                    new double[]{np != null ? np.getX() : 0.0, np != null ? np.getY() : 0.0});
        }
        edgeMovableIds.clear();
        edgeMovableOrigins.clear();
        edgeMovableIsComponent.clear();
        combineTargetId = null;
        stage.setDraggedElementId(draggingId);
        stage.setDraggedOverTrash(false);
        // Lease the component id — the server-side combine handler also walks owning-components, so
        // any cross-component cascade that touches this one will detect the conflict.
        openDragLease(java.util.List.of(comp.getId()));
        onDragStateChanged.accept(Boolean.TRUE);
    }

    private void beginDragFreeNode(CircuitNode node) {
        draggingId = node.getId();
        dragKind = DragKind.NODE;
        movedSinceDown = false;
        PositionInfo pos = node.getInfo(AllElementInfos.POSITION);
        originX = pos != null ? pos.getX() : 0.0;
        originY = pos != null ? pos.getY() : 0.0;
        originalNodePositions.clear();
        edgeMovableIds.clear();
        edgeMovableOrigins.clear();
        edgeMovableIsComponent.clear();
        combineTargetId = null;
        stage.setDraggedElementId(draggingId);
        stage.setDraggedOverTrash(false);
        openDragLease(java.util.List.of(node.getId()));
        onDragStateChanged.accept(Boolean.TRUE);
    }

    /**
     * Captures everything needed to translate an edge while keeping its endpoints' relative offset
     * stable. Each endpoint maps to a "movable":
     * <ul>
     *     <li>If the endpoint is a port of a component, the movable is the component centre — moving
     *         the centre by Δ replays through {@link com.minecart.registry.ComponentAnchorRegistry}
     *         and re-stamps every port at the new pose, which is exactly Δ-translated when there's
     *         no rotation (which we deliberately suppress during edge drag).</li>
     *     <li>Otherwise it's the free node itself.</li>
     * </ul>
     * Two endpoints owned by the same component collapse to a single movable (the component drags as
     * one). An endpoint that's an intrinsic non-port internal — which shouldn't normally appear here
     * because the renderer hides those — is silently skipped to avoid corrupting a component's pose.
     */
    private void beginDragEdge(CircuitEdge edge) {
        draggingId = edge.getId();
        dragKind = DragKind.EDGE;
        movedSinceDown = false;
        originalNodePositions.clear();
        edgeMovableIds.clear();
        edgeMovableOrigins.clear();
        edgeMovableIsComponent.clear();
        combineTargetId = null;

        for (int i = 0; i < 2; i++) {
            CircuitNode endpoint = edge.getConnection(i);
            if (endpoint == null) {
                continue;
            }
            CircuitComponent owner = endpoint.getComponent();
            if (owner != null) {
                if (!owner.isPort(endpoint)) {
                    // Intrinsic internal — not user-movable on its own, and dragging the parent here
                    // would be misleading because the user grabbed the wire, not the body. Bail out
                    // entirely so half-an-edge doesn't drag.
                    draggingId = null;
                    dragKind = null;
                    return;
                }
                UUID id = owner.getId();
                if (edgeMovableIds.add(id)) {
                    PositionInfo p = owner.getInfo(AllElementInfos.POSITION);
                    edgeMovableOrigins.put(id,
                            new double[]{p != null ? p.getX() : 0.0, p != null ? p.getY() : 0.0});
                    edgeMovableIsComponent.put(id, Boolean.TRUE);
                    // Component drag also drags every internal node by the same delta so port edges
                    // stay anchored — same machinery as a primary component drag, just keyed off a
                    // different drag-state path.
                    for (CircuitNode n : owner.getNodes()) {
                        PositionInfo np = n.getInfo(AllElementInfos.POSITION);
                        originalNodePositions.put(n.getId(),
                                new double[]{np != null ? np.getX() : 0.0,
                                             np != null ? np.getY() : 0.0});
                    }
                }
            } else {
                UUID id = endpoint.getId();
                if (edgeMovableIds.add(id)) {
                    PositionInfo p = endpoint.getInfo(AllElementInfos.POSITION);
                    edgeMovableOrigins.put(id,
                            new double[]{p != null ? p.getX() : 0.0, p != null ? p.getY() : 0.0});
                    edgeMovableIsComponent.put(id, Boolean.FALSE);
                }
            }
        }

        stage.setDraggedElementId(draggingId);
        stage.setDraggedOverTrash(false);
        // Lease the edge + every movable. Edge id keeps another client from grabbing the same wire;
        // each movable id (component centre or free node) blocks parallel translates of the same
        // backing element.
        java.util.List<UUID> ids = new java.util.ArrayList<>(edgeMovableIds.size() + 1);
        ids.add(edge.getId());
        ids.addAll(edgeMovableIds);
        openDragLease(ids);
        onDragStateChanged.accept(Boolean.TRUE);
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        // Right-press pivot-rotate gesture has priority over the normal drag path: a single
        // physical pointer can't be doing both at once anyway, and the right-press timer + drag
        // codepaths use completely independent state machines.
        if (rightGestureElementId != null) {
            processRightPressDrag(screenX, screenY);
            return true;
        }
        if (draggingId == null) {
            return false;
        }
        float[] w = stage.screenToWorld(screenX, screenY);
        double dx = w[0] - dragStartWorldX;
        double dy = w[1] - dragStartWorldY;
        if (dx != 0 || dy != 0) {
            movedSinceDown = true;
        }
        switch (dragKind) {
            case COMPONENT -> applyComponentDragDelta(dx, dy);
            case NODE -> {
                applyFreeNodeDragDelta(dx, dy);
                updateCombineTarget(w[0], w[1]);
            }
            case EDGE -> applyEdgeDragDelta(dx, dy);
        }
        stage.setDraggedOverTrash(isOverTrash(screenX, screenY));
        return true;
    }

    /**
     * Identifies the candidate "combine target" for a free-node drag by looking up which node the
     * cursor currently sits on (other than the dragged node itself). Result is exposed via
     * {@link #getCombineTargetId()} and consumed by {@link WorldStage#applyHighlightTints} to paint a
     * green/red affordance on the target.
     *
     * <p>The candidate must mutually accept the merge ({@code canCombine} on both sides) — and if it
     * lives in a component, its registry type must match the dragged node so the port slot stays
     * type-coherent. Mismatches still set {@link #combineTargetId} so the renderer can display a
     * "rejected" red tint, but {@link #touchUp} consults the same predicates again before sending
     * payloads.
     */
    private void updateCombineTarget(float wx, float wy) {
        combineTargetId = null;
        stage.setCombineTarget(null, false);
        // Skip the dragged actor — it follows the cursor so its bounds always contain (wx, wy), and
        // without an explicit exclude the (HashMap-ordered) lookup would sometimes return the dragged
        // node and miss the combine target sitting underneath. That non-determinism is what made
        // merges "work in one direction but not the other" depending on actor insertion order.
        var dropTarget = stage.findNodeActorAt(wx, wy, draggingId);
        if (dropTarget == null) {
            return;
        }
        CircuitNode candidate = dropTarget.getNode();
        combineTargetId = candidate.getId();
        // Validity matches trySendCombine's preflight: both sides accept, and a port absorbed needs a
        // type-matching survivor. Re-evaluating here means the user gets the green/red verdict in
        // real time rather than only at touchUp.
        boolean valid = isCombineValid(candidate);
        stage.setCombineTarget(combineTargetId, valid);
    }

    private boolean isCombineValid(CircuitNode candidate) {
        CircuitNode survivor = findAnyNode(draggingId);
        if (survivor == null || candidate == null || survivor == candidate) {
            return false;
        }
        // Mutual veto is the only gate: under the port-wins direction policy a free node dropped on
        // any-type port just absorbs into the port (no slot replacement happens), so the legacy
        // type-coherence check is gone. canCombine still refuses intrinsic non-port internals and
        // any subclass-specific overrides — that's enough.
        return survivor.canCombine(candidate) && candidate.canCombine(survivor);
    }

    private void applyComponentDragDelta(double dx, double dy) {
        CircuitComponent comp = findComponent(draggingId);
        if (comp == null) {
            return;
        }
        PositionInfo pos = comp.getInfo(AllElementInfos.POSITION);
        if (pos == null) {
            pos = new PositionInfo();
            comp.setInfo(AllElementInfos.POSITION, pos);
        }
        pos.set(originX + dx, originY + dy);
        // Translate every captured internal node by the same delta so port edges follow the body instead of
        // stretching while the user drags. The server will recompute proper anchor offsets when the
        // MoveElementPayload lands.
        for (CircuitNode n : comp.getNodes()) {
            double[] orig = originalNodePositions.get(n.getId());
            if (orig == null) {
                continue;
            }
            PositionInfo np = n.getInfo(AllElementInfos.POSITION);
            if (np == null) {
                np = new PositionInfo();
                n.setInfo(AllElementInfos.POSITION, np);
            }
            np.set(orig[0] + dx, orig[1] + dy);
        }
    }

    private void applyFreeNodeDragDelta(double dx, double dy) {
        CircuitNode node = findFreeNode(draggingId);
        if (node == null) {
            return;
        }
        PositionInfo pos = node.getInfo(AllElementInfos.POSITION);
        if (pos == null) {
            pos = new PositionInfo();
            node.setInfo(AllElementInfos.POSITION, pos);
        }
        pos.set(originX + dx, originY + dy);
    }

    /**
     * Translates every captured edge-drag movable by {@code (dx, dy)} so the wire's two endpoints
     * keep their relative offset (no rotation, no length change). Component movables also re-stamp
     * every internal node by the same delta — same machinery as {@link #applyComponentDragDelta} —
     * so port wires don't visibly snap back-and-forth between the rendered drag and the
     * server-confirmed pose.
     */
    private void applyEdgeDragDelta(double dx, double dy) {
        for (UUID id : edgeMovableIds) {
            double[] orig = edgeMovableOrigins.get(id);
            if (orig == null) {
                continue;
            }
            Boolean isComp = edgeMovableIsComponent.get(id);
            if (Boolean.TRUE.equals(isComp)) {
                CircuitComponent comp = findComponent(id);
                if (comp == null) {
                    continue;
                }
                PositionInfo pos = comp.getInfo(AllElementInfos.POSITION);
                if (pos == null) {
                    pos = new PositionInfo();
                    comp.setInfo(AllElementInfos.POSITION, pos);
                }
                pos.set(orig[0] + dx, orig[1] + dy);
                for (CircuitNode n : comp.getNodes()) {
                    double[] nodeOrig = originalNodePositions.get(n.getId());
                    if (nodeOrig == null) {
                        continue;
                    }
                    PositionInfo np = n.getInfo(AllElementInfos.POSITION);
                    if (np == null) {
                        np = new PositionInfo();
                        n.setInfo(AllElementInfos.POSITION, np);
                    }
                    np.set(nodeOrig[0] + dx, nodeOrig[1] + dy);
                }
            } else {
                CircuitNode node = findFreeNode(id);
                if (node == null) {
                    continue;
                }
                PositionInfo pos = node.getInfo(AllElementInfos.POSITION);
                if (pos == null) {
                    pos = new PositionInfo();
                    node.setInfo(AllElementInfos.POSITION, pos);
                }
                pos.set(orig[0] + dx, orig[1] + dy);
            }
        }
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (button == Input.Buttons.RIGHT) {
            return endRightPress();
        }
        if (button != Input.Buttons.LEFT || draggingId == null) {
            return false;
        }
        UUID worldId = selectedWorldId.get();
        UUID id = draggingId;
        UUID click = clickedId;
        DragKind kind = dragKind;
        boolean overTrash = isOverTrash(screenX, screenY);
        boolean moved = movedSinceDown;
        UUID combineTarget = combineTargetId;
        // Snapshot drag-time tables before clearing so the touchUp dispatch path can still walk the
        // captured movables / origins without races against a fresh drag start.
        java.util.LinkedHashSet<UUID> movables = new java.util.LinkedHashSet<>(edgeMovableIds);
        java.util.Map<UUID, double[]> movableOrigins = new java.util.HashMap<>(edgeMovableOrigins);
        java.util.Map<UUID, Boolean> movableIsComp = new java.util.HashMap<>(edgeMovableIsComponent);

        UUID gestureId = dragGestureId;
        // Clear local drag state before sending so the trashcan + tint disappear immediately.
        draggingId = null;
        clickedId = null;
        dragKind = null;
        combineTargetId = null;
        stage.setDraggedElementId(null);
        stage.setDraggedOverTrash(false);
        stage.setCombineTarget(null, false);
        onDragStateChanged.accept(Boolean.FALSE);

        if (worldId == null) {
            // No world to send into — still release the lease defensively so a future world
            // selection doesn't see stale lease state on the server. The Begin payload would have
            // been sent under the now-cleared dragWorldId anyway.
            closeDragLease();
            return true;
        }
        if (overTrash) {
            // Edges aren't deletable through the trashcan — they don't have their own delete
            // semantics in the protocol; the user removes wires by trashing one of their endpoints.
            // Keep the no-op silent rather than sending a payload the server would reject.
            if (kind != DragKind.EDGE) {
                connection.send(new DeleteElementPayload(worldId, id));
            }
            closeDragLease();
            return true;
        }
        if (!moved) {
            // Pure click — emit a "clicked element" signal so the editor can open the info panel
            // for the element the user actually aimed at, which may differ from the drag target
            // when the click landed on a port node owned by a component (drag drags the component,
            // click selects the port). Falls back to the drag id if no separate click target was
            // recorded. We don't open the panel here directly to keep DragController's dependency
            // footprint small (no UI Stage, no Skin, no payload imports). Swallow either way so
            // the click doesn't propagate to other input processors.
            onElementClicked.accept(worldId, click != null ? click : id);
            closeDragLease();
            return true;
        }
        switch (kind) {
            case COMPONENT -> {
                sendComponentMove(worldId, id);
                closeDragLease();
            }
            case NODE -> {
                if (combineTarget != null && trySendCombine(worldId, gestureId, id, combineTarget)) {
                    // Combine succeeded — close the lease AFTER sending the cascade so the
                    // server processes "cascade with active lease" then "release". Sequence
                    // matters: if release went first, the cascade would arrive after the lease
                    // is gone, which (a) makes the lease check trivially pass anyway (no lease
                    // held) but (b) breaks the multi-client guarantee that "this combine was
                    // serialised inside the drag's lease window".
                    closeDragLease();
                    return true;
                }
                sendFreeNodeMove(worldId, id);
                closeDragLease();
            }
            case EDGE -> {
                sendEdgeMovableMoves(worldId, movables, movableOrigins, movableIsComp);
                closeDragLease();
            }
        }
        return true;
    }

    private void sendComponentMove(UUID worldId, UUID id) {
        CircuitComponent comp = findComponent(id);
        if (comp == null) {
            return;
        }
        PositionInfo p = comp.getInfo(AllElementInfos.POSITION);
        if (p != null) {
            connection.send(new MoveElementPayload(worldId, id, p.getX(), p.getY()));
        }
    }

    private void sendFreeNodeMove(UUID worldId, UUID id) {
        CircuitNode node = findFreeNode(id);
        if (node == null) {
            return;
        }
        PositionInfo p = node.getInfo(AllElementInfos.POSITION);
        if (p != null) {
            connection.send(new MoveElementPayload(worldId, id, p.getX(), p.getY()));
        }
    }

    /**
     * Issues one {@link MoveElementPayload} per captured edge-drag movable so the server accepts
     * each as an authoritative pose update. Component movables hand off to
     * {@link com.minecart.server.handler.MoveElementHandler#moveComponent} which restamps every port
     * via {@link com.minecart.registry.ComponentAnchorRegistry}; free movables go through the
     * free-node path. The order is the order the movables were captured in {@link #beginDragEdge},
     * which is endpoint(0) before endpoint(1) — irrelevant for correctness but keeps the test
     * captures stable.
     */
    private void sendEdgeMovableMoves(UUID worldId,
                                      Set<UUID> movables,
                                      java.util.Map<UUID, double[]> origins,
                                      java.util.Map<UUID, Boolean> isComp) {
        for (UUID movId : movables) {
            double[] orig = origins.get(movId);
            if (orig == null) {
                continue;
            }
            Boolean component = isComp.get(movId);
            if (Boolean.TRUE.equals(component)) {
                CircuitComponent comp = findComponent(movId);
                if (comp == null) {
                    continue;
                }
                PositionInfo p = comp.getInfo(AllElementInfos.POSITION);
                if (p == null) {
                    continue;
                }
                connection.send(new MoveElementPayload(worldId, movId, p.getX(), p.getY()));
            } else {
                CircuitNode node = findFreeNode(movId);
                if (node == null) {
                    continue;
                }
                PositionInfo p = node.getInfo(AllElementInfos.POSITION);
                if (p == null) {
                    continue;
                }
                connection.send(new MoveElementPayload(worldId, movId, p.getX(), p.getY()));
            }
        }
    }

    /**
     * Dispatches a single atomic {@link CombineCascadePayload} for the dragged "survivor" node into
     * the {@code absorbedId} target. Returns {@code true} when the payload was sent (and the caller
     * therefore must NOT also send a fallback move payload), {@code false} when the combine was
     * pre-rejected on the client.
     *
     * <p>Before Phase 2c this method dispatched a granular three-way sequence
     * ({@code ReplaceComponentNodePayload} → {@code EdgeEndpointChangePayload}s →
     * {@code DeleteElementPayload}) that the server replayed step-by-step. The new cascade payload
     * lets the server plan-then-apply atomically through {@link com.minecart.logic.cascade.CombineCascadeEngine},
     * which gives us:
     * <ul>
     *     <li>Server-side direction policy (port wins) — the client doesn't have to swap survivor /
     *         absorbed before sending; the cascade engine normalises.</li>
     *     <li>Cross-component port-on-port handling (translate one component to bring its port to
     *         the other) — impossible to express in the granular protocol.</li>
     *     <li>Atomic rollback on lock conflict / geometry impossibility — partial granular sequences
     *         could previously leave the world in an inconsistent intermediate state if one of the
     *         three payloads was refused.</li>
     *     <li>A single payload to gate on the (future) drag lease, rather than three.</li>
     * </ul>
     *
     * <p>We still run the cheap mutual {@code canCombine} pre-check locally so an obviously-rejected
     * combine doesn't even hit the wire — same forgiving-client policy as the rest of the editor
     * payloads. The {@code gestureId} is currently {@code null}; Phase 2c drag-lease will populate
     * it with the active drag's id so the server can correlate the cascade with the held lease.
     */
    private boolean trySendCombine(UUID worldId, UUID gestureId,
                                   UUID survivorId, UUID absorbedId) {
        CircuitNode survivor = findAnyNode(survivorId);
        CircuitNode absorbed = findAnyNode(absorbedId);
        if (survivor == null || absorbed == null || survivor == absorbed) {
            return false;
        }
        if (!survivor.canCombine(absorbed) || !absorbed.canCombine(survivor)) {
            return false;
        }
        connection.send(new CombineCascadePayload(
                worldId,
                gestureId,
                List.of(new CombineCascadePayload.CombinePair(survivorId, absorbedId))));
        return true;
    }

    // --- Phase 2c drag-lease wiring -----------------------------------------------------------

    /**
     * Allocates a fresh {@link #dragGestureId} and dispatches a {@link DragBeginPayload} for the
     * given element ids. Called from every {@code beginDrag*()} entry point. Silent no-op when
     * {@link #selectedWorldId} returns {@code null} — we don't have a world to lease into, but the
     * drag still happens locally (the server will simply ignore the eventual Move payload too).
     */
    private void openDragLease(java.util.List<UUID> elementIds) {
        UUID worldId = selectedWorldId.get();
        dragWorldId = worldId;
        dragGestureId = UUID.randomUUID();
        leasedElementIds.clear();
        leasedElementIds.addAll(elementIds);
        if (worldId != null && !elementIds.isEmpty()) {
            connection.send(new DragBeginPayload(worldId, dragGestureId, leasedElementIds));
        }
    }

    /**
     * Releases the current drag lease, if any, by sending {@link DragEndPayload} and clearing the
     * local tracking fields. Idempotent — calling repeatedly is harmless (the second call just
     * sends nothing because {@code dragGestureId} is already null).
     */
    private void closeDragLease() {
        if (dragGestureId == null) {
            return;
        }
        if (dragWorldId != null) {
            connection.send(new DragEndPayload(dragWorldId, dragGestureId));
        }
        dragGestureId = null;
        dragWorldId = null;
        leasedElementIds.clear();
    }

    private boolean isOverTrash(int screenX, int screenY) {
        float[] bounds = trashBounds.get();
        if (bounds == null) {
            return false;
        }
        // The supplier returns coordinates in Scene2D screen space (origin bottom-left, y up). LibGDX's
        // input touch coordinates use top-left origin, so we flip y to match before comparing.
        float yFromBottom = com.badlogic.gdx.Gdx.graphics.getHeight() - screenY;
        return screenX >= bounds[0] && screenX <= bounds[0] + bounds[2]
                && yFromBottom >= bounds[1] && yFromBottom <= bounds[1] + bounds[3];
    }

    private CircuitComponent findComponent(UUID id) {
        var actor = stage.getComponentActor(id);
        return actor != null ? actor.getComponent() : null;
    }

    private CircuitNode findFreeNode(UUID id) {
        var actor = stage.getNodeActor(id);
        return actor != null ? actor.getNode() : null;
    }

    /**
     * Same lookup as {@link #findFreeNode(UUID)} since {@link WorldStage} materialises one node actor
     * per visible node — both free nodes and registered component ports — and intrinsic non-port
     * internals are never rendered. Kept as a separate helper so combine code reads at the right
     * abstraction (the node may or may not have a parent component) without leaking the assumption
     * that "node actor" excludes ports.
     */
    private CircuitNode findAnyNode(UUID id) {
        return findFreeNode(id);
    }

    // --- Phase 3c gestures --------------------------------------------------------------------

    /**
     * Scroll over a component → rotate it around the cursor pivot by {@link #SCROLL_ROTATION_STEP}
     * per notch. We only handle the over-element case (claiming the event) so empty-canvas scroll
     * still falls through to {@link CameraController#scrolled} for zoom. Sign convention: scrolling
     * up (negative {@code amountY} in libGDX) rotates counter-clockwise, matching most editor tools.
     *
     * <p>Edges aren't supported this phase — they need an endpoint-by-endpoint rotation cascade
     * which the engine doesn't yet expose. The event still falls through to camera zoom when the
     * cursor is over an edge, so the user just zooms; not ideal but doesn't break anything.
     */
    @Override
    public boolean scrolled(float amountX, float amountY) {
        if (Math.abs(amountY) < 1e-4f) {
            return false;
        }
        UUID worldId = selectedWorldId.get();
        if (worldId == null) {
            return false;
        }
        int sx = com.badlogic.gdx.Gdx.input.getX();
        int sy = com.badlogic.gdx.Gdx.input.getY();
        float[] w = stage.screenToWorld(sx, sy);
        CircuitElement el = stage.hitTestWorld(w[0], w[1]);
        CircuitComponent target = resolveRotationTarget(el);
        if (target == null) {
            return false;
        }
        // Rotating the actively-edited element would race the open panel's rotation field — refuse
        // here too so the lock-while-editing rule is consistent across drag / scroll / right-press
        // gestures.
        if (isEditingTarget(target)) {
            return true;
        }
        double delta = -amountY * SCROLL_ROTATION_STEP;
        connection.send(new RotateElementPayload(
                worldId, target.getId(), w[0], w[1], delta));
        return true;
    }

    /**
     * Resolves the rotation target for the gesture surface, applying the same Node>Component
     * disambiguation the click surface uses: a port node redirects to its parent component, since
     * rotating "just one port" isn't a coherent operation under the rigid-body anchor system.
     * Returns {@code null} for elements that can't be rotated (free nodes, edges, intrinsic
     * internals, anything not directly user-owned).
     */
    private CircuitComponent resolveRotationTarget(CircuitElement el) {
        if (el instanceof CircuitComponent comp) {
            return comp;
        }
        if (el instanceof CircuitNode node && node.getComponent() != null) {
            return node.getComponent();
        }
        return null;
    }

    /**
     * Starts the right-press timer if the press lands on a rotatable component (or a port redirecting
     * to one). Returns {@code true} when claimed so the camera doesn't pan. On empty canvas / non-
     * rotatable elements we return {@code false} so the camera's existing pan path takes over.
     */
    private boolean beginRightPress(int screenX, int screenY) {
        float[] w = stage.screenToWorld(screenX, screenY);
        CircuitElement el = stage.hitTestWorld(w[0], w[1]);
        CircuitComponent target = resolveRotationTarget(el);
        if (target == null) {
            return false;
        }
        // Lock-while-editing applies to the 1s right-press rotation too: claim the event so the
        // camera doesn't start panning either, but don't start the rotation timer.
        if (isEditingTarget(target)) {
            return true;
        }
        rightGestureElementId = target.getId();
        rightPressStartMs = System.currentTimeMillis();
        rightPressActive = false;
        rightPressPrevWorldX = w[0];
        rightPressPrevWorldY = w[1];
        return true;
    }

    /**
     * Per-frame drag tick while the right button is held. Activates the rotation once the
     * {@link #RIGHT_PRESS_ACTIVATION_MS} threshold has elapsed; until then cursor motion is
     * absorbed (no pan, no rotate) so the user has clear feedback that the gesture is queued.
     *
     * <p>Rotation delta is the angular sweep of the cursor around the component centre between the
     * previous and current frame — equivalently, how much the user "spun" the cursor about the
     * component. Pivot is the current cursor world position (per the spec: cursor is always the
     * pivot for gesture-driven rotation, even when the cursor moves between frames).
     */
    private void processRightPressDrag(int screenX, int screenY) {
        float[] w = stage.screenToWorld(screenX, screenY);
        if (!rightPressActive) {
            long elapsed = System.currentTimeMillis() - rightPressStartMs;
            if (elapsed < RIGHT_PRESS_ACTIVATION_MS) {
                // Still in the "hold to activate" phase. Refresh the prev cursor so the first
                // post-activation tick computes from the current cursor pos, not the press-down pos.
                rightPressPrevWorldX = w[0];
                rightPressPrevWorldY = w[1];
                return;
            }
            rightPressActive = true;
        }
        CircuitComponent comp = findComponent(rightGestureElementId);
        if (comp == null) {
            return;
        }
        PositionInfo pos = comp.getInfo(AllElementInfos.POSITION);
        if (pos == null) {
            return;
        }
        double cx = pos.getX();
        double cy = pos.getY();
        double prevAlpha = Math.atan2(rightPressPrevWorldY - cy, rightPressPrevWorldX - cx);
        double curAlpha = Math.atan2(w[1] - cy, w[0] - cx);
        double delta = normaliseAngle(curAlpha - prevAlpha);
        rightPressPrevWorldX = w[0];
        rightPressPrevWorldY = w[1];
        if (Math.abs(delta) < 1e-5) {
            return;
        }
        UUID worldId = selectedWorldId.get();
        if (worldId == null) {
            return;
        }
        connection.send(new RotateElementPayload(
                worldId, comp.getId(), w[0], w[1], delta));
    }

    /**
     * Releases the right-press gesture. Always returns {@code true} when a gesture was tracked so
     * the event doesn't propagate into the camera's pan-stop path and unexpectedly clear an
     * unrelated pan state. Returns {@code false} when no gesture was tracked so the camera can
     * resolve a pan-stop normally.
     */
    private boolean endRightPress() {
        if (rightGestureElementId == null) {
            return false;
        }
        rightGestureElementId = null;
        rightPressStartMs = 0L;
        rightPressActive = false;
        return true;
    }

    /** Normalises an angle delta into (-π, π] so a sweep across the discontinuity doesn't lurch. */
    private static double normaliseAngle(double a) {
        while (a <= -Math.PI) a += 2 * Math.PI;
        while (a > Math.PI) a -= 2 * Math.PI;
        return a;
    }

    /**
     * Returns {@code true} when {@code el} is the currently-edited element, OR when {@code el} is
     * a port redirecting to a parent component that's being edited. The port-redirect case mirrors
     * the drag-start path: a left click on a port normally routes its drag through the parent
     * component, so the lock has to extend through that same redirect — otherwise a user could
     * "drag the component by its port" while the component's panel is open and bypass the lock.
     */
    private boolean isEditingTarget(CircuitElement el) {
        UUID editing = editingElementId.get();
        if (editing == null || el == null) {
            return false;
        }
        if (editing.equals(el.getId())) {
            return true;
        }
        if (el instanceof CircuitNode node) {
            CircuitComponent owner = node.getComponent();
            return owner != null && editing.equals(owner.getId());
        }
        return false;
    }
}
