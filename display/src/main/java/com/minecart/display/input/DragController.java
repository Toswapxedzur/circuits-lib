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
import com.minecart.protocol.payload.client.DeleteElementPayload;
import com.minecart.protocol.payload.client.EdgeEndpointChangePayload;
import com.minecart.protocol.payload.client.MoveElementPayload;
import com.minecart.protocol.payload.client.ReplaceComponentNodePayload;
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
     * When dragging a free node, the id of the other node currently under the cursor (if any) — used
     * to render a "combine target" hint and to dispatch the combine payload sequence on touchUp. Reset
     * each frame in {@link #touchDragged}.
     */
    private UUID combineTargetId;

    public DragController(WorldStage stage,
                          ClientConnection connection,
                          Editor editor,
                          Supplier<UUID> selectedWorldId,
                          TrashBoundsSupplier trashBounds,
                          java.util.function.Consumer<Boolean> onDragStateChanged) {
        this.stage = stage;
        this.connection = connection;
        this.editor = editor;
        this.selectedWorldId = selectedWorldId;
        this.trashBounds = trashBounds;
        this.onDragStateChanged = onDragStateChanged != null ? onDragStateChanged : v -> {};
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
        // Edges, components, and nodes all paint a hover tint via WorldStage.applyHighlightTints,
        // which only knows how to colour component / node actors. Edges are still reported (so the
        // drag controller's edge-pickup branch can fire) but the hover tint stays on the renderable
        // sprite kinds that have a colour to apply.
        if (el instanceof CircuitComponent || el instanceof CircuitNode) {
            stage.setHoveredElementId(el.getId());
        } else {
            stage.setHoveredElementId(null);
        }
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
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
        if (el instanceof CircuitComponent comp) {
            beginDrag(comp);
            dragStartWorldX = w[0];
            dragStartWorldY = w[1];
            return true;
        }
        if (el instanceof CircuitNode node) {
            // Internal port nodes belong to their component's pose; route drag through the parent instead so
            // anchor offsets stay correct. If the user clicks an internal port directly, fall back to its
            // component. Belt-and-braces: also honour PositionInfo.isFixed() so a port whose component
            // pointer somehow didn't get linked client-side (stale mirror, replication race) still resists
            // being dragged off — no parent to redirect to in that case, so just swallow the click.
            if (node.getComponent() != null) {
                beginDrag(node.getComponent());
            } else if (isPositionFixed(node)) {
                return true;
            } else {
                beginDragFreeNode(node);
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
        onDragStateChanged.accept(Boolean.TRUE);
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
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
        if (!survivor.canCombine(candidate) || !candidate.canCombine(survivor)) {
            return false;
        }
        CircuitComponent absorbedComp = candidate.getComponent();
        if (absorbedComp != null && absorbedComp.isPort(candidate)) {
            if (!Objects.equals(survivor.getRegistryTypeId(), candidate.getRegistryTypeId())) {
                return false;
            }
            CircuitComponent survivorComp = survivor.getComponent();
            if (survivorComp != null && survivorComp != absorbedComp) {
                return false;
            }
        }
        return true;
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
        if (button != Input.Buttons.LEFT || draggingId == null) {
            return false;
        }
        UUID worldId = selectedWorldId.get();
        UUID id = draggingId;
        DragKind kind = dragKind;
        boolean overTrash = isOverTrash(screenX, screenY);
        boolean moved = movedSinceDown;
        UUID combineTarget = combineTargetId;
        // Snapshot drag-time tables before clearing so the touchUp dispatch path can still walk the
        // captured movables / origins without races against a fresh drag start.
        java.util.LinkedHashSet<UUID> movables = new java.util.LinkedHashSet<>(edgeMovableIds);
        java.util.Map<UUID, double[]> movableOrigins = new java.util.HashMap<>(edgeMovableOrigins);
        java.util.Map<UUID, Boolean> movableIsComp = new java.util.HashMap<>(edgeMovableIsComponent);

        // Clear local drag state before sending so the trashcan + tint disappear immediately.
        draggingId = null;
        dragKind = null;
        combineTargetId = null;
        stage.setDraggedElementId(null);
        stage.setDraggedOverTrash(false);
        stage.setCombineTarget(null, false);
        onDragStateChanged.accept(Boolean.FALSE);

        if (worldId == null) {
            return true;
        }
        if (overTrash) {
            // Edges aren't deletable through the trashcan — they don't have their own delete
            // semantics in the protocol; the user removes wires by trashing one of their endpoints.
            // Keep the no-op silent rather than sending a payload the server would reject.
            if (kind != DragKind.EDGE) {
                connection.send(new DeleteElementPayload(worldId, id));
            }
            return true;
        }
        if (!moved) {
            // Pure click — nothing else uses it yet (selection not implemented), so just swallow.
            return true;
        }
        switch (kind) {
            case COMPONENT -> sendComponentMove(worldId, id);
            case NODE -> {
                if (combineTarget != null && trySendCombine(worldId, id, combineTarget)) {
                    return true;
                }
                sendFreeNodeMove(worldId, id);
            }
            case EDGE -> sendEdgeMovableMoves(worldId, movables, movableOrigins, movableIsComp);
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
     * Attempts to dispatch the granular sequence of payloads that combines the dragged "survivor"
     * node into the {@code absorbedId} target. Returns {@code true} if the sequence was sent (and the
     * caller therefore must NOT also send a fallback move payload), {@code false} if the combine was
     * rejected.
     *
     * <p>Validation mirrors the server's: both sides must {@code canCombine}, and if the absorbed
     * node is a port of a component its registry type must match the survivor's. We re-check on the
     * client to avoid pushing a payload sequence the server would silently drop — drift between the
     * mirror and the authoritative state is rare but possible during a busy tick.
     *
     * <p>Payload order:
     * <ol>
     *     <li>{@link ReplaceComponentNodePayload} (if the absorbed node is a port) — runs first so
     *         the survivor becomes part of the component before any internal-strut endpoint change
     *         repoints onto it.</li>
     *     <li>For each non-self-loop edge incident to {@code absorbed}: an
     *         {@link EdgeEndpointChangePayload} that swaps {@code absorbed} for {@code survivor} on
     *         that edge. Self-loop edges (other endpoint == survivor) are dispatched as
     *         {@link DeleteElementPayload} since "an edge from a node to itself" isn't a meaningful
     *         circuit element.</li>
     *     <li>{@link DeleteElementPayload} for {@code absorbed} itself, after every edge has been
     *         repointed off it. By this point its component pointer (if any) was cleared by the
     *         port replace, so the standard free-node delete path applies.</li>
     * </ol>
     */
    private boolean trySendCombine(UUID worldId, UUID survivorId, UUID absorbedId) {
        CircuitNode survivor = findAnyNode(survivorId);
        CircuitNode absorbed = findAnyNode(absorbedId);
        if (survivor == null || absorbed == null || survivor == absorbed) {
            return false;
        }
        if (!survivor.canCombine(absorbed) || !absorbed.canCombine(survivor)) {
            return false;
        }
        CircuitComponent absorbedComp = absorbed.getComponent();
        boolean absorbedIsPort = absorbedComp != null && absorbedComp.isPort(absorbed);
        if (absorbedIsPort) {
            if (!Objects.equals(survivor.getRegistryTypeId(), absorbed.getRegistryTypeId())) {
                return false;
            }
            CircuitComponent survivorComp = survivor.getComponent();
            if (survivorComp != null && survivorComp != absorbedComp) {
                return false;
            }
        }

        // Snapshot incident edges BEFORE sending anything — the server's confirmation will trigger
        // local mirror updates that mutate the connection set as the deltas land, and walking a
        // mutating set is undefined behaviour on most Java collections.
        List<CircuitEdge> incident = new ArrayList<>(absorbed.getConnection());

        if (absorbedIsPort) {
            connection.send(new ReplaceComponentNodePayload(
                    worldId, absorbedComp.getId(), absorbedId, survivorId));
        }

        Set<UUID> seenEdges = new LinkedHashSet<>();
        for (CircuitEdge e : incident) {
            UUID eid = e.getId();
            if (!seenEdges.add(eid)) {
                continue;
            }
            CircuitNode other = e.getOther(absorbed);
            if (other == survivor) {
                // Would collapse into a self-loop on survivor — drop the wire instead. Lines up with
                // the server-side combineNodes branch that treats the same case as a delete.
                connection.send(new DeleteElementPayload(worldId, eid));
                continue;
            }
            UUID newStart = e.getStart() == absorbed ? survivorId
                    : (e.getStart() != null ? e.getStart().getId() : null);
            UUID newEnd = e.getEnd() == absorbed ? survivorId
                    : (e.getEnd() != null ? e.getEnd().getId() : null);
            if (newStart == null || newEnd == null || newStart.equals(newEnd)) {
                continue;
            }
            connection.send(new EdgeEndpointChangePayload(worldId, eid, newStart, newEnd));
        }

        connection.send(new DeleteElementPayload(worldId, absorbedId));
        return true;
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
}
