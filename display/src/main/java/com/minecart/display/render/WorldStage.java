package com.minecart.display.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.minecart.client.logic.ClientLevel;
import com.minecart.display.render.bounds.BoundingBoxRegistry;
import com.minecart.display.render.bounds.DefaultBoundingBoxes;
import com.minecart.display.render.registry.DefaultRenderRegistrations;
import com.minecart.display.render.registry.RenderContext;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Scene2D {@link Stage} that renders the live {@link ClientLevel} mirror through an
 * {@link OrthographicCamera}. World units are abstract; the camera's zoom / pixel-per-unit ratio is set via
 * {@link #setPixelsPerUnit}.
 *
 * <p>Each frame, before drawing, {@link #reconcile()} walks every world/circuit/node/edge/component and
 * adds an actor for newly-seen elements (or removes one whose element disappeared). Sufficient for the
 * editor's element counts; the client mirror does not currently fire add/remove events so a simple diff is
 * the cleanest synchronisation.
 *
 * <p>Actors are organised into three child {@link Group}s (edges below, nodes middle, components on top)
 * so visual layering is consistent regardless of insertion order.
 */
public class WorldStage extends Stage {

    private final ClientLevel level;
    private final Textures textures;
    private final OrthographicCamera camera;
    /**
     * Returns the id of the world the editor is currently focused on, or {@code null} when nothing is
     * selected. When non-null, {@link #reconcile()} only materialises actors for that single world — every
     * other world's elements are invisible to the camera so the canvas shows one world at a time and clicks
     * never hit stale geometry from a world the user isn't editing.
     */
    private final Supplier<UUID> selectedWorldId;

    private final GridBackgroundActor gridBackground = new GridBackgroundActor(this);
    private final Group edgesLayer = new Group();
    private final Group nodesLayer = new Group();
    private final Group componentsLayer = new Group();

    private final Map<UUID, NodeActor> nodeActors = new HashMap<>();
    private final Map<UUID, EdgeActor> edgeActors = new HashMap<>();
    private final Map<UUID, ComponentActor> componentActors = new HashMap<>();

    /** Working sets re-used by {@link #reconcile()} to find disappeared elements. */
    private final Set<UUID> seenNodes = new HashSet<>();
    private final Set<UUID> seenEdges = new HashSet<>();
    private final Set<UUID> seenComponents = new HashSet<>();

    private float pixelsPerUnit = 64f;

    /**
     * Cursor highlight states. The drag controller updates these every frame; {@link #applyHighlightTints()}
     * (called from {@link #act}) maps them to a per-actor {@link Color} so any actor's render path picks up
     * the tint via {@code getColor()}. Tints are reset to white each frame so movement of the hover/drag
     * focus doesn't leave a stuck colour on the previously highlighted actor.
     */
    private UUID hoveredElementId;
    private UUID draggedElementId;
    /** Set when a drag is currently positioned over the trashcan; turns the dragged actor red. */
    private boolean draggedOverTrash;
    /**
     * During a node drag, the id of the node currently underneath the cursor that the drop would
     * combine into (or {@code null} when no candidate is under the cursor). Painted green or red on
     * that actor depending on {@link #combineTargetValid} so the user can see at a glance whether
     * releasing the mouse will trigger a successful combine. Cleared automatically when the drag ends.
     */
    private UUID combineTargetId;
    private boolean combineTargetValid;
    /**
     * The element whose info panel is currently open. Painted with {@link #EDIT_TINT} so the user
     * can see at a glance that it's "forcibly locked while editing". Lives here (not in
     * {@code InfoPanelController}) so {@link #applyHighlightTints} can decide tint priority
     * uniformly with hover / drag / combine — edit wins over all of those because the user
     * shouldn't see "yellow hover" or "blue drag" on something they can't actually drag right now.
     */
    private UUID editingElementId;

    private static final Color HOVER_TINT = new Color(1.25f, 1.25f, 0.6f, 1f);
    private static final Color DRAG_TINT = new Color(0.8f, 1.1f, 1.6f, 1f);
    private static final Color TRASH_TINT = new Color(1.6f, 0.6f, 0.6f, 1f);
    private static final Color COMBINE_OK_TINT = new Color(0.6f, 1.6f, 0.6f, 1f);
    private static final Color COMBINE_BAD_TINT = new Color(1.6f, 0.6f, 0.6f, 1f);
    /**
     * Saturated amber for the element under active panel edit. Picked to be obviously distinct
     * from hover (light yellow), drag (cool blue), trash (red), and combine (green). The
     * red-orange skew also reads as "warning / locked" without being alarming.
     */
    private static final Color EDIT_TINT = new Color(1.6f, 1.0f, 0.35f, 1f);
    private static final Color RESET_TINT = new Color(1f, 1f, 1f, 1f);

    public WorldStage(ClientLevel level, Textures textures) {
        this(level, textures, null);
    }

    /**
     * @param selectedWorldId polled every frame to filter which worlds are visible; {@code null} (or a
     *     supplier returning {@code null}) means "render nothing" so the canvas is blank when no world is
     *     chosen. Passing a supplier rather than a UUID lets the editor switch selection without rebuilding
     *     the stage.
     */
    public WorldStage(ClientLevel level, Textures textures, Supplier<UUID> selectedWorldId) {
        super(new ScreenViewport(new OrthographicCamera()));
        DefaultRenderRegistrations.install();
        BoundingBoxRegistry.installDefaults();
        this.level = level;
        this.textures = textures;
        this.selectedWorldId = selectedWorldId;
        this.camera = (OrthographicCamera) getViewport().getCamera();
        ScreenViewport sv = (ScreenViewport) getViewport();
        sv.setUnitsPerPixel(1f / pixelsPerUnit);
        // Layer order: coordinate grid in back so the circuit sits on top, then edges, nodes, components.
        addActor(gridBackground);
        addActor(edgesLayer);
        addActor(nodesLayer);
        addActor(componentsLayer);
    }

    public ClientLevel getLevel() {
        return level;
    }

    public Textures getTextures() {
        return textures;
    }

    public RenderContext createRenderContext(CircuitElement element, Actor actor, Batch batch, float parentAlpha) {
        UUID id = element.getId();
        boolean dragged = id != null && id.equals(draggedElementId);
        boolean combine = id != null && id.equals(combineTargetId);
        return new RenderContext(
                this,
                actor,
                textures,
                batch,
                parentAlpha,
                actor.getColor(),
                id != null && id.equals(hoveredElementId),
                dragged,
                dragged && draggedOverTrash,
                id != null && id.equals(editingElementId),
                combine,
                combine && combineTargetValid);
    }

    public OrthographicCamera getCamera() {
        return camera;
    }

    public Group getNodesLayer() {
        return nodesLayer;
    }

    public Group getEdgesLayer() {
        return edgesLayer;
    }

    public EdgeActor getEdgeActor(UUID id) {
        return edgeActors.get(id);
    }

    public Group getComponentsLayer() {
        return componentsLayer;
    }

    public float getPixelsPerUnit() {
        return pixelsPerUnit;
    }

    public void setPixelsPerUnit(float p) {
        this.pixelsPerUnit = Math.max(1f, p);
        ScreenViewport sv = (ScreenViewport) getViewport();
        sv.setUnitsPerPixel(1f / pixelsPerUnit);
        getViewport().update((int) sv.getScreenWidth(), (int) sv.getScreenHeight(), false);
    }

    /**
     * Returns the current camera zoom expressed as a magnification percent: 100% = identity (camera.zoom 1),
     * 200% = zoomed in 2x (camera.zoom 0.5), 50% = zoomed out 2x (camera.zoom 2). Surface this to the user
     * rather than camera.zoom so values are intuitive.
     */
    public float getMagnificationPercent() {
        return 100f / camera.zoom;
    }

    /**
     * Inverse of {@link #getMagnificationPercent()}. Clamps to a tiny positive lower bound to avoid
     * division-by-zero / NaN; the rest of the editor (slider, scroll-wheel) enforces the friendly
     * 5%..1600% range on the input side.
     */
    public void setMagnificationPercent(float pct) {
        camera.zoom = 100f / Math.max(0.0001f, pct);
        camera.update();
    }

    @Override
    public void act(float delta) {
        reconcile();
        applyHighlightTints();
        super.act(delta);
    }

    /** Sets the element id the cursor is currently hovering, or {@code null} to clear. */
    public void setHoveredElementId(UUID id) {
        this.hoveredElementId = id;
    }

    /** Sets the element id currently being dragged, or {@code null} to clear. */
    public void setDraggedElementId(UUID id) {
        this.draggedElementId = id;
    }

    /** When dragging, marks whether the cursor is currently over the trashcan so the actor turns red. */
    public void setDraggedOverTrash(boolean overTrash) {
        this.draggedOverTrash = overTrash;
    }

    /**
     * Updates the combine-target affordance on the node the cursor is currently over (during a node
     * drag). Pass {@code null} to clear; pass a non-null id with {@code valid=true} to paint green or
     * {@code valid=false} to paint red. The tint is applied each frame in
     * {@link #applyHighlightTints()} alongside hover / drag / trash highlights.
     */
    public void setCombineTarget(UUID id, boolean valid) {
        this.combineTargetId = id;
        this.combineTargetValid = valid;
    }

    /**
     * Marks the element whose info panel is currently open so {@link #applyHighlightTints} can paint
     * it with the {@link #EDIT_TINT}. Pass {@code null} when the panel closes. Called by
     * {@code InfoPanelController} from its acquire/release-lease helpers.
     */
    public void setEditingElementId(UUID id) {
        this.editingElementId = id;
    }

    public UUID getEditingElementId() {
        return editingElementId;
    }

    public UUID getHoveredElementId() {
        return hoveredElementId;
    }

    public UUID getDraggedElementId() {
        return draggedElementId;
    }

    /**
     * Resets every tracked actor's colour to white, then paints the dragged actor (priority: trash-red or
     * drag-blue) and finally the hovered actor (yellow). Running this every frame from {@link #act} keeps
     * the highlight pinned to the live cursor target even when the mouse moves between reconciliations.
     */
    private void applyHighlightTints() {
        for (NodeActor a : nodeActors.values()) {
            a.setColor(RESET_TINT);
        }
        for (ComponentActor a : componentActors.values()) {
            a.setColor(RESET_TINT);
        }
        for (EdgeActor a : edgeActors.values()) {
            a.setColor(RESET_TINT);
        }
        if (draggedElementId != null) {
            Color c = draggedOverTrash ? TRASH_TINT : DRAG_TINT;
            tint(draggedElementId, c);
        }
        // Combine target paints over hover so the green/red affordance wins when the cursor is over a
        // valid drop target — the user shouldn't see "yellow hover" on a node that's about to absorb
        // the dragged one. Skip if the combine target IS the dragged actor (defensive: shouldn't
        // happen, but keeps drag tint priority intact).
        if (combineTargetId != null && !combineTargetId.equals(draggedElementId)) {
            tint(combineTargetId, combineTargetValid ? COMBINE_OK_TINT : COMBINE_BAD_TINT);
        }
        if (hoveredElementId != null
                && !hoveredElementId.equals(draggedElementId)
                && !hoveredElementId.equals(combineTargetId)) {
            tint(hoveredElementId, HOVER_TINT);
        }
        // Edit tint paints LAST so it overrides hover / drag / combine on the actively-edited
        // element. The user can't drag a panel-locked element anyway, so masking those other
        // states with the amber is the right visual feedback ("this element is busy").
        if (editingElementId != null) {
            tint(editingElementId, EDIT_TINT);
        }
    }

    private void tint(UUID id, Color c) {
        ComponentActor ca = componentActors.get(id);
        if (ca != null) {
            ca.setColor(c);
            return;
        }
        NodeActor na = nodeActors.get(id);
        if (na != null) {
            na.setColor(c);
            return;
        }
        // Edges (wires, resistors, batteries, etc.) all draw via EdgeActor.draw() which honours
        // getColor() across both the body sprite and the bridge tiles, so the same tint pipeline
        // works without per-kind plumbing.
        EdgeActor ea = edgeActors.get(id);
        if (ea != null) {
            ea.setColor(c);
        }
    }

    public NodeActor getNodeActor(UUID id) {
        return nodeActors.get(id);
    }

    public ComponentActor getComponentActor(UUID id) {
        return componentActors.get(id);
    }

    /**
     * Adds actors for any new elements and removes actors whose element vanished from the mirror.
     * Cheap O(elements) walk; fine for editor sizes.
     */
    public void reconcile() {
        seenNodes.clear();
        seenEdges.clear();
        seenComponents.clear();

        // Per-world isolation: when a selection supplier is wired in, only the focused world is walked so
        // actors for every other world fall through the sweep below and disappear from the canvas. A null
        // supplier preserves the legacy behaviour of rendering everything (kept for the default
        // constructor and any existing call sites that don't care about world isolation).
        UUID selected = selectedWorldId != null ? selectedWorldId.get() : null;
        boolean hasSelection = selectedWorldId != null;

        if (!hasSelection || selected != null) {
            for (World world : level.getWorlds()) {
                if (hasSelection && !selected.equals(world.getId())) {
                    continue;
                }
                for (Circuit circuit : world.getCircuits()) {
                    for (CircuitNode node : circuit.nodes()) {
                        // Render rule for nodes owned by a component: only the public ports are drawn,
                        // because users need the port dots as affordances for dropping wires. Hidden
                        // internal nodes (e.g. a BJT's centre junction used solely for the
                        // constitutive equation) are skipped so they neither show through the body
                        // sprite nor become unintended click targets. Free nodes (no owning component)
                        // are always rendered.
                        CircuitComponent owner = node.getComponent();
                        if (owner != null && !owner.isPort(node)) {
                            continue;
                        }
                        UUID id = node.getId();
                        seenNodes.add(id);
                        if (!nodeActors.containsKey(id)) {
                            NodeActor actor = new NodeActor(node, textures);
                            nodeActors.put(id, actor);
                            nodesLayer.addActor(actor);
                        }
                    }
                    for (CircuitEdge edge : circuit.edges()) {
                        // Hide internal wiring edges: a wire tagged with a component is part of that
                        // component's private star graph and shouldn't draw on top of the component's
                        // body sprite.
                        if (edge.getComponent() != null) {
                            continue;
                        }
                        UUID id = edge.getId();
                        seenEdges.add(id);
                        if (!edgeActors.containsKey(id)) {
                            EdgeActor actor = new EdgeActor(edge, textures);
                            edgeActors.put(id, actor);
                            edgesLayer.addActor(actor);
                        }
                    }
                    for (CircuitComponent comp : circuit.components()) {
                        UUID id = comp.getId();
                        seenComponents.add(id);
                        if (!componentActors.containsKey(id)) {
                            ComponentActor actor = new ComponentActor(comp, textures);
                            componentActors.put(id, actor);
                            componentsLayer.addActor(actor);
                        }
                    }
                }
            }
        }

        sweep(nodeActors, seenNodes);
        sweep(edgeActors, seenEdges);
        sweep(componentActors, seenComponents);
    }

    private static <A extends com.badlogic.gdx.scenes.scene2d.Actor> void sweep(
            Map<UUID, A> actors, Set<UUID> seen) {
        if (actors.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, A>> it = actors.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, A> entry = it.next();
            if (!seen.contains(entry.getKey())) {
                entry.getValue().remove();
                it.remove();
            }
        }
    }

    /** Maps a screen point to a world-space point under the camera (no editor / palette translation). */
    public float[] screenToWorld(float screenX, float screenY) {
        com.badlogic.gdx.math.Vector3 v = new com.badlogic.gdx.math.Vector3(screenX, screenY, 0);
        camera.unproject(v);
        return new float[]{v.x, v.y};
    }

    /**
     * @return the topmost {@link CircuitElement} whose actor's bounds contain {@code (worldX, worldY)},
     *         or {@code null} if nothing is hit. Priority is nodes &gt; components &gt; edges.
     *
     * <p>Nodes win over components because a node sprite drawn over a component body (e.g. a port
     * node on a transistor) should be independently selectable — clicking the node opens the
     * node-level info panel even though the parent component is also under the cursor. The drag
     * controller still redirects a port-node DRAG to its parent component so anchor offsets stay
     * consistent (see {@link com.minecart.display.input.DragController#touchDown}), but a CLICK
     * (no movement) on a port node fires for the node itself.
     *
     * <p>Edges are tested last so a click on a junction (node sprite straddling an edge endpoint)
     * picks the node, not the edge underneath. Edge hit-testing uses
     * {@link #pointToSegmentDistance} with a tolerance of {@link EdgeActor#THICKNESS} (the visible wire
     * thickness in world units) so the click area matches the painted wire and the user doesn't have
     * to land on a sub-pixel line. Edges with missing endpoints / positions are skipped.
     */
    public CircuitElement hitTestWorld(float worldX, float worldY) {
        for (NodeActor a : nodeActors.values()) {
            if (BoundingBoxRegistry.contains(a.getNode(), a, worldX, worldY)) {
                return a.getNode();
            }
        }
        for (ComponentActor a : componentActors.values()) {
            if (BoundingBoxRegistry.contains(a.getComponent(), a, worldX, worldY)) {
                return a.getComponent();
            }
        }
        EdgeActor edgeHit = hitTestEdge(worldX, worldY);
        if (edgeHit != null) {
            return edgeHit.getEdge();
        }
        return null;
    }

    /**
     * @return the topmost rendered {@link EdgeActor} whose painted wire passes within
     *         {@link EdgeActor#THICKNESS} world units of {@code (worldX, worldY)}, or {@code null} if
     *         no edge is close enough. Used by the editor's drag controller so dragging the wire (not
     *         just one of its endpoint nodes) translates both endpoints together.
     */
    public EdgeActor hitTestEdge(float worldX, float worldY) {
        float bestDist = Float.MAX_VALUE;
        EdgeActor best = null;
        for (EdgeActor a : edgeActors.values()) {
            CircuitEdge e = a.getEdge();
            if (!BoundingBoxRegistry.contains(e, a, worldX, worldY)) {
                continue;
            }
            CircuitNode n1 = e.getConnection(0);
            CircuitNode n2 = e.getConnection(1);
            if (n1 == null || n2 == null) {
                return a;
            }
            com.minecart.variant.info.PositionInfo p1 =
                    n1.getInfo(com.minecart.registry.AllElementInfos.POSITION);
            com.minecart.variant.info.PositionInfo p2 =
                    n2.getInfo(com.minecart.registry.AllElementInfos.POSITION);
            if (p1 == null || p2 == null) {
                return a;
            }
            float ax = (float) p1.getX();
            float ay = (float) p1.getY();
            float bx = (float) p2.getX();
            float by = (float) p2.getY();
            float d = DefaultBoundingBoxes.pointToSegmentDistance(worldX, worldY, ax, ay, bx, by);
            if (d < bestDist) {
                bestDist = d;
                best = a;
            }
        }
        return best;
    }

    public GridBackgroundActor getGridBackground() {
        return gridBackground;
    }

    @Override
    public void dispose() {
        gridBackground.dispose();
        super.dispose();
    }

    public NodeActor findNodeActorAt(float worldX, float worldY) {
        return findNodeActorAt(worldX, worldY, null);
    }

    /**
     * Same as {@link #findNodeActorAt(float, float)} but skips the actor whose node id matches
     * {@code excludeId} and, when multiple actors overlap the cursor, returns the one whose centre is
     * closest. The exclusion is needed for combine-target detection during a node drag: the dragged
     * actor follows the cursor and its bounds therefore contain the cursor point, so without skipping
     * it the HashMap iteration order would sometimes return the dragged actor and the caller would
     * never see the actual drop target underneath. Closest-centre tiebreaking handles the case where
     * the cursor sits over two stacked candidate nodes — we want the visually-intended target, not
     * whichever happened to be inserted first.
     */
    public NodeActor findNodeActorAt(float worldX, float worldY, UUID excludeId) {
        NodeActor best = null;
        float bestDistSq = Float.MAX_VALUE;
        for (NodeActor a : nodeActors.values()) {
            if (excludeId != null && excludeId.equals(a.getNode().getId())) {
                continue;
            }
            if (!BoundingBoxRegistry.contains(a.getNode(), a, worldX, worldY)) {
                continue;
            }
            float cx = a.getX() + a.getWidth() * 0.5f;
            float cy = a.getY() + a.getHeight() * 0.5f;
            float dx = worldX - cx;
            float dy = worldY - cy;
            float distSq = dx * dx + dy * dy;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = a;
            }
        }
        return best;
    }
}
