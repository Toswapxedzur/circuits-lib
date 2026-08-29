package com.minecart.display.render.engine;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The scene: the Create-style split of static vs. moving geometry.
 * <ul>
 *   <li><b>Static</b> — every component's static boxes (+ the board) are merged into ONE neighbour-culled
 *       {@link PartMesh} ({@link #build}), so faces hidden by the board or an adjacent component are dropped.
 *       Drawn once (identity transform); rebuilt only on a board edit. Not instanced.</li>
 *   <li><b>Movable</b> — movable part-types are GPU-instanced: one {@link PartMesh} + draw call per type,
 *       transforms updated per frame from each component's animation.</li>
 *   <li><b>Dynamic entity</b> — a free-moving world {@link DynamicEntity}: a whole {@link ComponentModel} drawn
 *       at an arbitrary per-frame transform (its physics pose — <b>rotation included</b>, unlike the
 *       translation-only static bake). One instanced {@link PartMesh} per entity, one instance at its pose.</li>
 * </ul>
 * All faces sample one {@link PartAtlas} (built in {@link #build}, once every box — hence every sprite — is
 * known). Back-face culling is on (winding CCW-from-outside).
 */
final class EngineRenderer implements Disposable {

    /**
     * A free-moving world entity: a whole {@link ComponentModel} drawn at an arbitrary per-frame transform (its
     * physics pose). Unlike a static placement — whose boxes are merged into the scene mesh with translation
     * only ({@link ComponentInstance#collectStatic}) — an entity is GPU-instanced with a <b>full matrix</b>, so
     * it renders correctly when it tumbles or rests at an angle. Set {@link #pose} each frame from physics.
     */
    static final class DynamicEntity {
        final ComponentModel model;
        final Matrix4 pose = new Matrix4();

        DynamicEntity(ComponentModel model) {
            this.model = model;
        }

        /** Sets the world transform used for the next {@link EngineRenderer#render} (the physics pose). */
        void pose(Matrix4 world) {
            pose.set(world);
        }
    }

    // GL3+ core context → hardware instancing; the live app's GL2.0 context → one draw per instance (u_world).
    private final boolean instanced = Gdx.gl30 != null;
    private final ShaderProgram shader = InstancedShader.create(instanced);
    private final Matrix4 identity = new Matrix4();

    private final List<ComponentInstance> components = new ArrayList<>();
    private final List<PartMesh.Box> extraStatic = new ArrayList<>();
    private final Map<PartType, List<ComponentInstance.PartInstance>> movableBuckets = new LinkedHashMap<>();
    private final Map<PartType, PartMesh> movableMeshes = new LinkedHashMap<>();
    private final List<DynamicEntity> entities = new ArrayList<>();
    private final Map<DynamicEntity, PartMesh> entityMeshes = new IdentityHashMap<>();
    private PartAtlas atlas;
    private PartMesh staticOpaque;
    private PartMesh staticTranslucent;

    /** Places a component: its static boxes join the scene mesh (on {@link #build}); its movables are instanced. */
    void add(ComponentInstance c) {
        components.add(c);
        for (ComponentInstance.PartInstance p : c.movables) {
            movableBuckets.computeIfAbsent(p.type, k -> new ArrayList<>()).add(p);
        }
    }

    /** Adds standalone static geometry in world space (e.g. the board slab). */
    void addStatic(List<PartMesh.Box> worldBoxes) {
        extraStatic.addAll(worldBoxes);
    }

    /** Registers a free-moving world entity (its own instanced mesh, drawn at {@link DynamicEntity#pose}). */
    void addEntity(DynamicEntity e) {
        entities.add(e);
    }

    /** Drops all registered entities (e.g. to repopulate from a new board snapshot). Their meshes are freed on
     *  the next {@link #build()}; call build() after re-adding. */
    void clearEntities() {
        entities.clear();
    }

    /**
     * Bakes the atlas and every mesh. Order matters: collect all boxes → stitch the atlas from their sprites →
     * bake the static scene mesh (neighbour-culled) and one instanced mesh per movable type. Call after all
     * {@link #add}s / on a board edit.
     */
    void build() {
        disposeMeshes();

        List<PartMesh.Box> all = new ArrayList<>(extraStatic);
        List<PartMesh.Quad> allQuads = new ArrayList<>();
        for (ComponentInstance c : components) {
            c.collectStatic(all);
            c.collectQuads(allQuads);
        }

        // Every sprite any face can request: static boxes + quads + every movable part-type's local boxes + every
        // dynamic entity's model. These extra lists feed atlas-name collection ONLY — they are NOT baked into the
        // scene mesh (which stays `all`/`allQuads`); the movable/entity meshes are instanced separately below.
        List<PartMesh.Box> forAtlas = new ArrayList<>(all);
        List<PartMesh.Quad> quadsForAtlas = new ArrayList<>(allQuads);
        for (PartType type : movableBuckets.keySet()) {
            forAtlas.addAll(type.boxes());
        }
        for (DynamicEntity e : entities) {
            forAtlas.addAll(e.model.staticBoxes);
            quadsForAtlas.addAll(e.model.staticQuads);
        }
        Set<String> names = new LinkedHashSet<>();
        PartMesh.collectSpriteNames(forAtlas, quadsForAtlas, names);
        atlas = new PartAtlas(names);

        // Split by translucency: opaque parts (+ all quads) render first; translucent parts (e.g. the LED's
        // glass core) render after, blended, without writing depth.
        List<PartMesh.Box> opaque = new ArrayList<>();
        List<PartMesh.Box> translucent = new ArrayList<>();
        for (PartMesh.Box b : all) (b.translucent() ? translucent : opaque).add(b);
        staticOpaque = PartMesh.of(opaque, allQuads, 1, atlas, instanced);
        staticTranslucent = PartMesh.of(translucent, List.of(), 1, atlas, instanced);
        for (Map.Entry<PartType, List<ComponentInstance.PartInstance>> e : movableBuckets.entrySet()) {
            movableMeshes.put(e.getKey(),
                    PartMesh.of(e.getKey().boxes(), List.of(), Math.max(1, e.getValue().size()), atlas, instanced));
        }
        // One single-instance mesh per entity (its whole model, object space). Rendered in the opaque pass at its
        // pose. (Entities are assumed opaque — the battery cell is; a translucent-boxed entity would need the same
        // opaque/translucent split as the scene mesh, added when one exists.)
        for (DynamicEntity e : entities) {
            entityMeshes.put(e, PartMesh.of(e.model.staticBoxes, e.model.staticQuads, 1, atlas, instanced));
        }
    }

    /** Eases every component's animation and recomputes its movable parts' world matrices. */
    void update(float dt) {
        for (ComponentInstance c : components) {
            c.update(dt);
        }
    }

    void render(Camera cam) {
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        Gdx.gl.glCullFace(GL20.GL_BACK);

        shader.bind();
        shader.setUniformMatrix("u_projView", cam.combined);
        atlas.texture().bind(0);
        shader.setUniformi("u_atlas", 0);

        // Milestone 5a lighting: a moderate ambient + a soft directional key light from above-front, so parts
        // read with gentle shading (top faces brighter) instead of flat fullbright. Point lights (LEDs) fill the
        // arrays in 5b; here u_numLights = 0.
        shader.setUniformf("u_ambient", 0.60f, 0.60f, 0.62f);
        shader.setUniformf("u_lightDir", 0.337f, 0.842f, 0.421f);   // normalized, TO the light
        shader.setUniformf("u_lightColor", 0.45f, 0.45f, 0.42f);
        shader.setUniformi("u_numLights", 0);

        // --- Opaque pass: scene mesh (world space) + every movable type, depth-written. ---
        if (staticOpaque != null) {
            staticOpaque.begin();
            staticOpaque.add(identity);
            staticOpaque.render(shader);
        }
        for (Map.Entry<PartType, List<ComponentInstance.PartInstance>> e : movableBuckets.entrySet()) {
            PartMesh mesh = movableMeshes.get(e.getKey());
            mesh.begin();
            for (ComponentInstance.PartInstance p : e.getValue()) {
                mesh.add(p.world);
            }
            mesh.render(shader);
        }
        for (DynamicEntity e : entities) {                       // free-moving entities at their physics pose
            PartMesh mesh = entityMeshes.get(e);
            mesh.begin();
            mesh.add(e.pose);
            mesh.render(shader);
        }

        // --- Translucent pass: alpha-blended, depth-TESTED but not depth-WRITTEN (glass over the opaque cores). ---
        if (staticTranslucent != null) {
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            Gdx.gl.glDepthMask(false);
            staticTranslucent.begin();
            staticTranslucent.add(identity);
            staticTranslucent.render(shader);
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
    }

    private void disposeMeshes() {
        if (staticOpaque != null) {
            staticOpaque.dispose();
            staticOpaque = null;
        }
        if (staticTranslucent != null) {
            staticTranslucent.dispose();
            staticTranslucent = null;
        }
        for (PartMesh m : movableMeshes.values()) {
            m.dispose();
        }
        movableMeshes.clear();
        for (PartMesh m : entityMeshes.values()) {
            m.dispose();
        }
        entityMeshes.clear();
        if (atlas != null) {
            atlas.dispose();
            atlas = null;
        }
    }

    @Override
    public void dispose() {
        shader.dispose();
        disposeMeshes();
    }
}
