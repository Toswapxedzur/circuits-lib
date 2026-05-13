package com.minecart.display.render;

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

    public WorldStage(ClientLevel level, Textures textures) {
        super(new ScreenViewport(new OrthographicCamera()));
        this.level = level;
        this.textures = textures;
        this.camera = (OrthographicCamera) getViewport().getCamera();
        ScreenViewport sv = (ScreenViewport) getViewport();
        sv.setUnitsPerPixel(1f / pixelsPerUnit);
        // Layer order: edges underneath, then nodes, then components (sprites overlap).
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

    @Override
    public void act(float delta) {
        reconcile();
        super.act(delta);
    }

    /**
     * Adds actors for any new elements and removes actors whose element vanished from the mirror.
     * Cheap O(elements) walk; fine for editor sizes.
     */
    public void reconcile() {
        seenNodes.clear();
        seenEdges.clear();
        seenComponents.clear();

        for (World world : level.getWorlds()) {
            for (Circuit circuit : world.getCircuits()) {
                for (CircuitNode node : circuit.nodes()) {
                    UUID id = node.getId();
                    seenNodes.add(id);
                    if (!nodeActors.containsKey(id)) {
                        NodeActor actor = new NodeActor(node, textures);
                        nodeActors.put(id, actor);
                        nodesLayer.addActor(actor);
                    }
                }
                for (CircuitEdge edge : circuit.edges()) {
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

    public NodeActor findNodeActorAt(float worldX, float worldY) {
        for (NodeActor a : nodeActors.values()) {
            if (contains(a, worldX, worldY)) {
                return a;
            }
        }
        return null;
    }
}
