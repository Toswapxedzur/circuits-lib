package com.minecart.display.render.engine;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
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
        Color light = null;       // optional point-light emission (a moving entity that glows); null = none
        float lightRange = 0f;

        DynamicEntity(ComponentModel model) {
            this.model = model;
        }

        /** Makes this entity a point light of {@code colour} / {@code range} world units (fluent). */
        DynamicEntity emit(Color colour, float range) {
            this.light = colour;
            this.lightRange = range;
            return this;
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

    // Point-light collection buffers (LEDs / glowing entities) — filled each frame, pushed to the shader arrays.
    private final float[] lightPos = new float[InstancedShader.MAX_LIGHTS * 3];
    private final float[] lightCol = new float[InstancedShader.MAX_LIGHTS * 3];
    private final float[] lightRng = new float[InstancedShader.MAX_LIGHTS];
    private final Vector3 tmpEmit = new Vector3();

    // Shadow map (the directional key light casts real shadows). Depth pass uses the depth shader; the main
    // shader samples the map. Aimed at the scene's bounds (computed at build()).
    private static final Vector3 LIGHT_DIR = new Vector3(0.337f, 0.842f, 0.421f); // normalized, TO the light
    // The shadow-map depth pass is built + wired, but the depth comparison currently over-shadows the whole
    // scene (a systematic offset not yet pinned). Gated OFF until fixed — lighting (ambient + directional + LED
    // point lights) works fully without it. Flip to true to resume debugging the shadow depth.
    private static final boolean SHADOWS = false;
    private final ShaderProgram depthShader = DepthShader.create(instanced);
    private ShadowMap shadowMap;
    private final Vector3 sceneCentre = new Vector3();
    private float sceneRadius = 100f;

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

        // Bounds for the shadow-map light camera (over the static world boxes; movables/entities live within).
        computeSceneBounds(all);
        if (shadowMap == null) {
            shadowMap = new ShadowMap(2048);
        }
    }

    private void computeSceneBounds(List<PartMesh.Box> worldBoxes) {
        if (worldBoxes.isEmpty()) {
            sceneCentre.set(0f, 0f, 0f);
            sceneRadius = 100f;
            return;
        }
        float minx = Float.MAX_VALUE, miny = minx, minz = minx, maxx = -minx, maxy = -minx, maxz = -minx;
        for (PartMesh.Box b : worldBoxes) {
            minx = Math.min(minx, b.cx() - b.sx() / 2f); maxx = Math.max(maxx, b.cx() + b.sx() / 2f);
            miny = Math.min(miny, b.cy() - b.sy() / 2f); maxy = Math.max(maxy, b.cy() + b.sy() / 2f);
            minz = Math.min(minz, b.cz() - b.sz() / 2f); maxz = Math.max(maxz, b.cz() + b.sz() / 2f);
        }
        sceneCentre.set((minx + maxx) / 2f, (miny + maxy) / 2f, (minz + maxz) / 2f);
        float dx = maxx - minx, dy = maxy - miny, dz = maxz - minz;
        sceneRadius = 0.5f * (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * 1.15f; // enclosing sphere + pad
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
        Gdx.gl.glDisable(GL20.GL_DITHER); // dithering would corrupt the RGBA-packed shadow depth

        // --- Shadow pass: render the opaque scene's depth from the light's ortho POV into the shadow map. ---
        if (shadowMap != null && SHADOWS) {
            shadowMap.begin(sceneCentre, sceneRadius, LIGHT_DIR);
            depthShader.bind();
            depthShader.setUniformMatrix("u_projView", shadowMap.viewProj());
            renderOpaqueMeshes(depthShader);
            shadowMap.end();
            Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        }

        shader.bind();
        shader.setUniformMatrix("u_projView", cam.combined);

        // Lighting: a moderate ambient + a soft directional key light (which casts the shadow map), plus the
        // point lights (LEDs) collected below.
        shader.setUniformf("u_ambient", 0.60f, 0.60f, 0.62f);
        shader.setUniformf("u_lightDir", LIGHT_DIR.x, LIGHT_DIR.y, LIGHT_DIR.z);
        shader.setUniformf("u_lightColor", 0.45f, 0.45f, 0.42f);
        applyPointLights();
        if (shadowMap != null && SHADOWS) {
            shader.setUniformMatrix("u_lightViewProj", shadowMap.viewProj());
            shadowMap.texture().bind(1);
            shader.setUniformi("u_shadowMap", 1);
            shader.setUniformf("u_shadowStrength", 1f);
        } else {
            shader.setUniformf("u_shadowStrength", 0f);
        }
        // Bind the atlas LAST so the active texture unit ends at 0 — some drivers mis-sample otherwise.
        atlas.texture().bind(0);
        shader.setUniformi("u_atlas", 0);

        // --- Opaque pass: scene mesh (world space) + every movable type + entities, depth-written, lit + shadowed. ---
        renderOpaqueMeshes(shader);

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

    /** Renders the opaque geometry (scene mesh + movables + entities) with {@code sh} — used by both the shadow
     *  depth pass and the lit main pass. */
    private void renderOpaqueMeshes(ShaderProgram sh) {
        if (staticOpaque != null) {
            staticOpaque.begin();
            staticOpaque.add(identity);
            staticOpaque.render(sh);
        }
        for (Map.Entry<PartType, List<ComponentInstance.PartInstance>> e : movableBuckets.entrySet()) {
            PartMesh mesh = movableMeshes.get(e.getKey());
            mesh.begin();
            for (ComponentInstance.PartInstance p : e.getValue()) {
                mesh.add(p.world);
            }
            mesh.render(sh);
        }
        for (DynamicEntity e : entities) {                       // free-moving entities at their physics pose
            PartMesh mesh = entityMeshes.get(e);
            mesh.begin();
            mesh.add(e.pose);
            mesh.render(sh);
        }
    }

    /** Gathers up to {@link InstancedShader#MAX_LIGHTS} point lights (emitting components + glowing entities) and
     *  pushes them to the shader's light arrays. Called once per frame before the passes. */
    private void applyPointLights() {
        int nl = 0;
        for (ComponentInstance c : components) {
            if (nl >= InstancedShader.MAX_LIGHTS) break;
            ComponentEntity e = c.entity;
            if (e.emits()) {
                c.emitterWorld(tmpEmit);
                putLight(nl++, tmpEmit, e.light, e.lightRange);
            }
        }
        for (DynamicEntity en : entities) {
            if (nl >= InstancedShader.MAX_LIGHTS) break;
            if (en.light != null && en.lightRange > 0f) {
                en.pose.getTranslation(tmpEmit);
                putLight(nl++, tmpEmit, en.light, en.lightRange);
            }
        }
        shader.setUniformi("u_numLights", nl);
        if (nl > 0) {
            shader.setUniform3fv("u_lightPos", lightPos, 0, nl * 3);
            shader.setUniform3fv("u_lightColor2", lightCol, 0, nl * 3);
            shader.setUniform1fv("u_lightRange", lightRng, 0, nl);
        }
    }

    private void putLight(int i, Vector3 pos, Color c, float range) {
        lightPos[i * 3] = pos.x; lightPos[i * 3 + 1] = pos.y; lightPos[i * 3 + 2] = pos.z;
        lightCol[i * 3] = c.r; lightCol[i * 3 + 1] = c.g; lightCol[i * 3 + 2] = c.b;
        lightRng[i] = range;
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
        depthShader.dispose();
        if (shadowMap != null) {
            shadowMap.dispose();
        }
        disposeMeshes();
    }
}
