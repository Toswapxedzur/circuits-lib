package com.minecart.display.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.minecart.client.logic.ClientLevel;
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

    private static final Color HOVER_TINT = new Color(1.25f, 1.25f, 0.6f, 1f);
    private static final Color DRAG_TINT = new Color(0.8f, 1.1f, 1.6f, 1f);
    private static final Color TRASH_TINT = new Color(1.6f, 0.6f, 0.6f, 1f);
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

    public OrthographicCamera getCamera() {
        return camera;
    }

    public Group getNodesLayer() {
        return nodesLayer;
    }

    public Group getEdgesLayer() {
        return edgesLayer;
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
        if (draggedElementId != null) {
            Color c = draggedOverTrash ? TRASH_TINT : DRAG_TINT;
            tint(draggedElementId, c);
        }
        if (hoveredElementId != null && !hoveredElementId.equals(draggedElementId)) {
            tint(hoveredElementId, HOVER_TINT);
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
                        // Skip internal port / centre nodes — they live inside their parent component's
                        // sprite and shouldn't draw a separate dot. Without this filter the BJT (and any
                        // future multi-port component) shows its private star graph on top of the body.
                        if (node.getComponent() != null) {
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
                        // Same idea for edges: an edge tagged with a component is part of that component's
                        // internal wiring and should be invisible to the user.
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
     *         or {@code null} if nothing is hit. Components → nodes → edges in that priority.
     */
    public CircuitElement hitTestWorld(float worldX, float worldY) {
        for (ComponentActor a : componentActors.values()) {
            if (contains(a, worldX, worldY)) {
                return a.getComponent();
            }
        }
        for (NodeActor a : nodeActors.values()) {
            if (contains(a, worldX, worldY)) {
                return a.getNode();
            }
        }
        return null;
    }

    private static boolean contains(com.badlogic.gdx.scenes.scene2d.Actor a, float wx, float wy) {
        return wx >= a.getX() && wx <= a.getX() + a.getWidth()
                && wy >= a.getY() && wy <= a.getY() + a.getHeight();
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
        for (NodeActor a : nodeActors.values()) {
            if (contains(a, worldX, worldY)) {
                return a;
            }
        }
        return null;
    }
}
