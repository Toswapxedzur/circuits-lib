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
        Color bodyTint = null;    // optional whole-body colour multiplier (e.g. a red LED); null = untinted
        // Movable sub-parts (switch knob, dial…): each recomputes its world = pose · local · motion(anim) so a
        // user-driven channel actually moves the piece. Empty for parts with no movables.
        final AnimationState anim = new AnimationState();
        final List<ComponentInstance.PartInstance> movables = new ArrayList<>();
        private final Matrix4 motionTmp = new Matrix4();

        DynamicEntity(ComponentModel model) {
            this.model = model;
            for (ComponentModel.MovablePart m : model.movableParts) {
                anim.channel(m.binding().channel(), 0f, 0f, 8f); // declared at rest; the interaction drives it
                movables.add(new ComponentInstance.PartInstance(m.type(), m.local(), m.binding().toBinding()));
            }
        }

        /** Recomputes each movable's world matrix from the current pose + animation (call after pose/channel change). */
        void updateMovables() {
            for (ComponentInstance.PartInstance p : movables) {
                p.world.set(pose).mul(p.local).mul(p.binding.motion(anim, motionTmp));
            }
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
    // The SKYLIGHT — the world's default natural light. Default 45° pitch / 45° yaw (owner spec); TO the light.
    // (45° pitch: y=sin45; 45° yaw of the horizontal component: x=z=cos45·cos45.) Configurable via setLightDir;
    // user-placed lights (LED point lights) stack additively on top of this base in the shader.
    private final Vector3 lightDir = new Vector3(0.5f, 0.7071f, 0.5f); // 45°/45°, |·|=1
    /** Sets the skylight direction (TO the light; will be normalised). Default 45° pitch / 45° yaw. */
    public void setLightDir(float x, float y, float z) {
        lightDir.set(x, y, z).nor();
    }
    // Directional shadow map — WORKING (verified: parts cast soft shadows on the board slab, no acne, in both the
    // GL3.2 demo and the GL2.0 app). A hardware 24-bit DEPTH TEXTURE (see ShadowMap/DepthShader), sampled .r with a
    // 3x3 PCF in the main shader. The light ortho is fit to a TIGHT world AABB each frame (updateFrameBounds →
    // sceneCentre/sceneHalf) with vertical outliers clamped, so the limited depth range is spent on the parts (this
    // + the depth texture fixed the old self-shadow acne). DepthShader must declare the SAME vertex attributes as
    // the main shader — omitting them left stale VAO arrays enabled → GL_INVALID_OPERATION that blanked the scene.
    // On by default; -Dsnap.shadows=off disables (also the fail-safe path if a driver rejects the depth-texture FBO).
    private static final boolean SHADOWS = !"off".equals(System.getProperty("snap.shadows"));
    private final ShaderProgram depthShader = DepthShader.create(instanced);
    private ShadowMap shadowMap;
    private final Vector3 sceneCentre = new Vector3();
    private final Vector3 sceneHalf = new Vector3(100f, 100f, 100f); // tight AABB half-extents for the light ortho
    private final Vector3 staticMin = new Vector3(), staticMax = new Vector3(); // static-box bounds (from build)
    private boolean hasStaticBounds;
    private final Vector3 tmpPos = new Vector3();
    private static final float PART_MARGIN = 30f; // pad around each movable/entity position (covers a part's size)
    private static final float MAX_SHADOW_HEIGHT = 80f; // clamp vertical extent so a tall floater can't starve depth

    private final List<ComponentInstance> components = new ArrayList<>();
    private final List<PartMesh.Box> extraStatic = new ArrayList<>();
    private final Map<PartType, List<ComponentInstance.PartInstance>> movableBuckets = new LinkedHashMap<>();
    private final Map<PartType, PartMesh> movableMeshes = new LinkedHashMap<>();
    private final List<DynamicEntity> entities = new ArrayList<>();
    private final Map<DynamicEntity, PartMesh> entityMeshes = new IdentityHashMap<>();
    private final Map<PartType, PartMesh> entityMovableMeshes = new LinkedHashMap<>(); // one instanced mesh per knob type
    private PartAtlas atlas;
    private PartMesh staticOpaque;
    private PartMesh staticTranslucent;

    // Placement-ghost models: registered up-front so their sprites are in the atlas + a mesh is baked per model,
    // ready to draw translucent at an eased pose (the "shadow" preview of where the current tool would place).
    private final Map<String, ComponentModel> ghostModels = new LinkedHashMap<>();
    private final Map<String, PartMesh> ghostMeshes = new LinkedHashMap<>();
    private final com.badlogic.gdx.graphics.Color tmpTint = new com.badlogic.gdx.graphics.Color();

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

    /** Registers a model that the placement ghost may preview, so its sprites join the atlas and a mesh is baked
     *  for it on {@link #build()}. Call for every tool's model before build; drawn via {@link #drawGhost}. */
    void addGhostModel(String id, ComponentModel model) {
        ghostModels.put(id, model);
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
            for (ComponentInstance.PartInstance p : e.movables) forAtlas.addAll(p.type.boxes()); // knob sprites
        }
        for (ComponentModel g : ghostModels.values()) { // ghost-preview models — sprites must be in the atlas
            forAtlas.addAll(g.staticBoxes);
            quadsForAtlas.addAll(g.staticQuads);
        }
        Set<String> names = new LinkedHashSet<>();
        PartMesh.collectSpriteNames(forAtlas, quadsForAtlas, names);
        atlas = new PartAtlas(names, PaletteDither.Octant.of(lightDir.x, lightDir.z)); // baked skylight variant

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
        // A mesh per movable knob type across all entities (instanced — one draw per type, one instance per knob).
        entityMovableMeshes.clear();
        Map<PartType, Integer> movCount = new LinkedHashMap<>();
        for (DynamicEntity e : entities) {
            for (ComponentInstance.PartInstance p : e.movables) movCount.merge(p.type, 1, Integer::sum);
        }
        for (Map.Entry<PartType, Integer> mc : movCount.entrySet()) {
            entityMovableMeshes.put(mc.getKey(), PartMesh.of(mc.getKey().boxes(), List.of(), mc.getValue(), atlas, instanced));
        }
        for (Map.Entry<String, ComponentModel> g : ghostModels.entrySet()) {
            ghostMeshes.put(g.getKey(),
                    PartMesh.of(g.getValue().staticBoxes, g.getValue().staticQuads, 1, atlas, instanced));
        }

        // Bounds for the shadow-map light camera (over the static world boxes; movables/entities live within).
        computeSceneBounds(all);
        if (shadowMap == null && SHADOWS) {
            try {
                shadowMap = new ShadowMap(2048);
            } catch (RuntimeException e) {
                // A driver that rejects the depth-texture FBO (some GL2.0 contexts): degrade to no shadows.
                com.badlogic.gdx.Gdx.app.error("EngineRenderer", "shadow map unavailable, shadows off: " + e.getMessage());
                shadowMap = null;
            }
        }
    }

    /** Stores the STATIC world-box bounds at build time (may be empty — the board/game render parts as entities). */
    private void computeSceneBounds(List<PartMesh.Box> worldBoxes) {
        hasStaticBounds = false;
        if (worldBoxes.isEmpty()) {
            return;
        }
        float minx = Float.MAX_VALUE, miny = minx, minz = minx, maxx = -minx, maxy = -minx, maxz = -minx;
        for (PartMesh.Box b : worldBoxes) {
            minx = Math.min(minx, b.cx() - b.sx() / 2f); maxx = Math.max(maxx, b.cx() + b.sx() / 2f);
            miny = Math.min(miny, b.cy() - b.sy() / 2f); maxy = Math.max(maxy, b.cy() + b.sy() / 2f);
            minz = Math.min(minz, b.cz() - b.sz() / 2f); maxz = Math.max(maxz, b.cz() + b.sz() / 2f);
        }
        staticMin.set(minx, miny, minz);
        staticMax.set(maxx, maxy, maxz);
        hasStaticBounds = true;
    }

    /** Recomputes the shadow bounds this frame — the static boxes PLUS every movable/entity's CURRENT position
     *  (padded by a part's size), so the light camera always covers what's actually rendered (parts live on the
     *  entity/movable paths, so build-time static bounds alone miss them). */
    private void updateFrameBounds() {
        boolean any = hasStaticBounds;
        float minx, miny, minz, maxx, maxy, maxz;
        if (hasStaticBounds) {
            minx = staticMin.x; miny = staticMin.y; minz = staticMin.z;
            maxx = staticMax.x; maxy = staticMax.y; maxz = staticMax.z;
        } else {
            minx = miny = minz = Float.MAX_VALUE;
            maxx = maxy = maxz = -Float.MAX_VALUE;
        }
        for (Map.Entry<PartType, List<ComponentInstance.PartInstance>> e : movableBuckets.entrySet()) {
            for (ComponentInstance.PartInstance p : e.getValue()) {
                p.world.getTranslation(tmpPos);
                any = true;
                minx = Math.min(minx, tmpPos.x - PART_MARGIN); maxx = Math.max(maxx, tmpPos.x + PART_MARGIN);
                miny = Math.min(miny, tmpPos.y - PART_MARGIN); maxy = Math.max(maxy, tmpPos.y + PART_MARGIN);
                minz = Math.min(minz, tmpPos.z - PART_MARGIN); maxz = Math.max(maxz, tmpPos.z + PART_MARGIN);
            }
        }
        for (DynamicEntity en : entities) {
            en.pose.getTranslation(tmpPos);
            any = true;
            minx = Math.min(minx, tmpPos.x - PART_MARGIN); maxx = Math.max(maxx, tmpPos.x + PART_MARGIN);
            miny = Math.min(miny, tmpPos.y - PART_MARGIN); maxy = Math.max(maxy, tmpPos.y + PART_MARGIN);
            minz = Math.min(minz, tmpPos.z - PART_MARGIN); maxz = Math.max(maxz, tmpPos.z + PART_MARGIN);
        }
        if (!any) {
            sceneCentre.set(0f, 0f, 0f);
            sceneHalf.set(100f, 100f, 100f);
            return;
        }
        // Clamp the vertical extent: parts sit near the board plane, so a lone floater must not stretch the depth
        // range and starve the flat parts of precision (the old acne). Keep the box centred on the bulk (min side).
        if (maxy - miny > MAX_SHADOW_HEIGHT) {
            maxy = miny + MAX_SHADOW_HEIGHT;
        }
        // Cap the horizontal span so the fixed-res map keeps fine texels; geometry beyond simply renders unshadowed.
        float cx = (minx + maxx) / 2f, cz = (minz + maxz) / 2f;
        float hx = Math.min(300f, Math.max(20f, (maxx - minx) / 2f + 5f));
        float hz = Math.min(300f, Math.max(20f, (maxz - minz) / 2f + 5f));
        float hy = Math.max(20f, (maxy - miny) / 2f + 5f);
        sceneCentre.set(cx, (miny + maxy) / 2f, cz);
        sceneHalf.set(hx, hy, hz);
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
        Gdx.gl.glDisable(GL20.GL_DITHER); // dithering would corrupt the GL2.0 path's RGBA-packed shadow depth

        // --- Shadow pass: render the opaque scene's depth from the light's ortho POV into the shadow map. ---
        if (shadowMap != null && SHADOWS) {
            updateFrameBounds(); // cover the parts (entities/movables), which build-time static bounds miss
            shadowMap.begin(sceneCentre, sceneHalf, lightDir);
            depthShader.bind();
            depthShader.setUniformMatrix("u_projView", shadowMap.viewProj());
            renderOpaqueMeshes(depthShader);
            shadowMap.end();
            Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        }

        shader.bind();
        shader.setUniformMatrix("u_projView", cam.combined);

        // Lighting: the skylight directional is BAKED into the sprites (per-octant), so u_ambient is ~1.0 — it
        // passes that baked base through — and the point lights (LEDs) stack on top. u_lightDir is still needed
        // for the shadow-map bias below.
        shader.setUniformf("u_ambient", 1.0f, 1.0f, 1.0f);
        shader.setUniformf("u_tint", 1f, 1f, 1f, 1f); // parts draw untinted; the ghost overrides this per-draw
        shader.setUniformf("u_lightDir", lightDir.x, lightDir.y, lightDir.z);
        applyPointLights();
        if (shadowMap != null && SHADOWS) {
            shader.setUniformMatrix("u_lightViewProj", shadowMap.viewProj());
            shadowMap.texture().bind(1);
            shader.setUniformi("u_shadowMap", 1);
            shader.setUniformf("u_shadowStrength", 1f);
            shader.setUniformf("u_shadowTexel", 1f / 2048f);
            // Small depth-space bias; a hardware 24-bit depth texture over a tight AABB needs little slope offset.
            shader.setUniformf("u_shadowBias", 0.0016f);
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

    /** Draws the placement <b>ghost</b>: the registered {@code modelId} model at {@code pose}, blended at
     *  {@code (r,g,b,a)}. Call AFTER {@link #render} (same frame/camera). No shadow/point-lights on the preview.
     *
     *  <p>Uses a <b>depth pre-pass</b> so the translucent model reads as a clean single layer: a multi-box part
     *  drawn blended in one pass shows its own back/interior faces bleeding through (an X-ray mess). Pass 1 writes
     *  only DEPTH (nearest surface); pass 2 blends only fragments at that depth — so just the frontmost skin of the
     *  ghost is shown, like a solid part that happens to be see-through. Depth-tested against real parts (occluded
     *  correctly) but never depth-written to the scene (glDepthMask stays false in pass 2). */
    void drawGhost(Camera cam, String modelId, Matrix4 pose, float r, float g, float b, float a) {
        PartMesh mesh = ghostMeshes.get(modelId);
        if (mesh == null) {
            return;
        }
        shader.bind();
        shader.setUniformMatrix("u_projView", cam.combined);
        shader.setUniformf("u_ambient", 1f, 1f, 1f);
        shader.setUniformf("u_tint", r, g, b, a);
        shader.setUniformf("u_shadowStrength", 0f);
        shader.setUniformi("u_numLights", 0);
        atlas.texture().bind(0);
        shader.setUniformi("u_atlas", 0);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        Gdx.gl.glCullFace(GL20.GL_BACK);
        mesh.begin();
        mesh.add(pose);

        // Pass 1 — depth only: fills the depth buffer with the ghost's nearest surface (no colour, no blend).
        Gdx.gl.glColorMask(false, false, false, false);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        mesh.render(shader);

        // Pass 2 — blended colour of ONLY that frontmost surface (LEQUAL passes it, deeper interior faces fail).
        Gdx.gl.glColorMask(true, true, true, true);
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        mesh.render(shader);

        // Restore default scene state.
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        Gdx.gl.glDepthFunc(GL20.GL_LESS);
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
        boolean tinted = false;
        for (DynamicEntity e : entities) {                       // free-moving entities at their physics pose
            PartMesh mesh = entityMeshes.get(e);
            if (e.bodyTint != null) {                            // per-entity colour (e.g. a red LED body)
                sh.setUniformf("u_tint", e.bodyTint.r, e.bodyTint.g, e.bodyTint.b, e.bodyTint.a);
                tinted = true;
            } else if (tinted) {
                sh.setUniformf("u_tint", 1f, 1f, 1f, 1f);        // reset after a tinted entity
                tinted = false;
            }
            mesh.begin();
            mesh.add(e.pose);
            mesh.render(sh);
        }
        if (tinted) {
            sh.setUniformf("u_tint", 1f, 1f, 1f, 1f);           // leave u_tint white for later passes
        }
        // Movable sub-parts (knobs/sliders/dials), one instanced draw per type at each knob's animated world matrix.
        for (Map.Entry<PartType, PartMesh> mm : entityMovableMeshes.entrySet()) {
            PartMesh mesh = mm.getValue();
            mesh.begin();
            for (DynamicEntity e : entities) {
                e.updateMovables();
                for (ComponentInstance.PartInstance p : e.movables) {
                    if (p.type == mm.getKey()) mesh.add(p.world);
                }
            }
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
        for (PartMesh m : entityMovableMeshes.values()) {
            m.dispose();
        }
        entityMovableMeshes.clear();
        for (PartMesh m : ghostMeshes.values()) {
            m.dispose();
        }
        ghostMeshes.clear();
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
