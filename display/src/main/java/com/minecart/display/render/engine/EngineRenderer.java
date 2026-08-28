package com.minecart.display.render.engine;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;

import java.util.ArrayList;
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
 * </ul>
 * All faces sample one {@link PartAtlas} (built in {@link #build}, once every box — hence every sprite — is
 * known). Back-face culling is on (winding CCW-from-outside).
 */
final class EngineRenderer implements Disposable {

    private final ShaderProgram shader = InstancedShader.create();
    private final Matrix4 identity = new Matrix4();

    private final List<ComponentInstance> components = new ArrayList<>();
    private final List<PartMesh.Box> extraStatic = new ArrayList<>();
    private final Map<PartType, List<ComponentInstance.PartInstance>> movableBuckets = new LinkedHashMap<>();
    private final Map<PartType, PartMesh> movableMeshes = new LinkedHashMap<>();
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

        // Every sprite any face can request: static boxes + quads + every movable part-type's local boxes.
        List<PartMesh.Box> forAtlas = new ArrayList<>(all);
        for (PartType type : movableBuckets.keySet()) {
            forAtlas.addAll(type.boxes());
        }
        Set<String> names = new LinkedHashSet<>();
        PartMesh.collectSpriteNames(forAtlas, allQuads, names);
        atlas = new PartAtlas(names);

        // Split by translucency: opaque parts (+ all quads) render first; translucent parts (e.g. the LED's
        // glass core) render after, blended, without writing depth.
        List<PartMesh.Box> opaque = new ArrayList<>();
        List<PartMesh.Box> translucent = new ArrayList<>();
        for (PartMesh.Box b : all) (b.translucent() ? translucent : opaque).add(b);
        staticOpaque = PartMesh.of(opaque, allQuads, 1, atlas);
        staticTranslucent = PartMesh.of(translucent, List.of(), 1, atlas);
        for (Map.Entry<PartType, List<ComponentInstance.PartInstance>> e : movableBuckets.entrySet()) {
            movableMeshes.put(e.getKey(),
                    PartMesh.of(e.getKey().boxes(), List.of(), Math.max(1, e.getValue().size()), atlas));
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
