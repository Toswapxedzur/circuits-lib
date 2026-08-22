package com.minecart.display.render.snap;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.minecart.snap.SnapBoard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The dramatic, non-navigable backdrop around the snap board — a gradient sunset sky plus layered scenery
 * (mountains, hills, trees, clouds). The scenery is real {@link Model}s rendered through libGDX's
 * {@code DefaultShader}, so it receives the shared {@link Environment}'s directional sun, ambient, fog, and
 * — the point of Phase R1 — <b>real-time cast shadows</b>. The sky is a separate hand-shaded gradient dome
 * with a glowing sun (it neither casts nor receives shadows).
 */
public final class ToonRenderer implements Disposable {

    private static final Logger log = LoggerFactory.getLogger(ToonRenderer.class);

    private static final String SKY_VERT =
            "attribute vec3 a_position;\n" +
            "uniform mat4 u_projViewTrans;\n" +
            "uniform mat4 u_worldTrans;\n" +
            "varying vec3 v_dir;\n" +
            "void main() {\n" +
            "  v_dir = normalize(a_position);\n" +
            "  gl_Position = u_projViewTrans * u_worldTrans * vec4(a_position, 1.0);\n" +
            "}\n";

    private static final String SKY_FRAG =
            "#ifdef GL_ES\nprecision mediump float;\n#endif\n" +
            "varying vec3 v_dir;\n" +
            "uniform vec3 u_zenith;\n" +
            "uniform vec3 u_horizon;\n" +
            "uniform vec3 u_skyGround;\n" +
            "uniform vec3 u_sunDir;\n" +
            "uniform vec3 u_sunColor;\n" +
            "void main() {\n" +
            "  float y = v_dir.y;\n" +
            "  vec3 col = (y > 0.0)\n" +
            "    ? mix(u_horizon, u_zenith, pow(clamp(y, 0.0, 1.0), 0.55))\n" +
            "    : mix(u_horizon, u_skyGround, clamp(-y * 2.0, 0.0, 1.0));\n" +
            "  float s = max(dot(normalize(v_dir), normalize(u_sunDir)), 0.0);\n" +
            "  col += u_sunColor * pow(s, 220.0) * 2.0;\n" +
            "  col += u_sunColor * pow(s, 7.0) * 0.35;\n" +
            "  gl_FragColor = vec4(col, 1.0);\n" +
            "}\n";

    private static final Color SKY_ZENITH = new Color(0.14f, 0.13f, 0.32f, 1f);
    private static final Color SKY_HORIZON = new Color(0.98f, 0.60f, 0.38f, 1f);
    private static final Color SKY_GROUND = new Color(0.15f, 0.10f, 0.17f, 1f);
    private static final Color SUN_COLOR = new Color(1.0f, 0.86f, 0.62f, 1f);

    private static final Color GROUND = new Color(0.36f, 0.50f, 0.30f, 1f);
    private static final Color MOUNTAIN = new Color(0.34f, 0.30f, 0.46f, 1f);
    private static final Color HILL = new Color(0.33f, 0.48f, 0.31f, 1f);
    private static final Color TRUNK = new Color(0.42f, 0.28f, 0.16f, 1f);
    private static final Color FOLIAGE = new Color(0.21f, 0.46f, 0.24f, 1f);
    private static final Color CLOUD = new Color(1.0f, 0.84f, 0.76f, 1f);

    /** Sun direction TO the light (matches the shared Environment's DirectionalShadowLight, negated). */
    public static final Vector3 SUN_TO_LIGHT = new Vector3(0.45f, 0.62f, 0.4f).nor();

    private final Environment environment;
    private final ModelBatch modelBatch = new ModelBatch();

    private final ShaderProgram sky;
    private final Mesh skyMesh;

    private final Model groundModel;
    private final Model mountainModel;
    private final Model hillModel;
    private final Model cloudModel;
    private final Model treeModel;

    private final ModelInstance groundInstance;
    private final List<ModelInstance> mountains = new ArrayList<>();
    private final List<ModelInstance> hills = new ArrayList<>();
    private final List<ModelInstance> clouds = new ArrayList<>();
    private final List<ModelInstance> trees = new ArrayList<>();
    private final List<ModelInstance> casters = new ArrayList<>();
    private final Matrix4 skyWorld = new Matrix4();

    public ToonRenderer(SnapBoard board, Environment sharedEnvironment) {
        this.environment = sharedEnvironment;
        ShaderProgram.pedantic = false;
        this.sky = compile(SKY_VERT, SKY_FRAG, "sky");
        this.skyMesh = skySphere();

        long attrs = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal;
        ModelBuilder mb = new ModelBuilder();
        this.groundModel = mb.createBox(8000f, 4f, 8000f, mat(GROUND), attrs);
        this.mountainModel = coneBaseAtZero(mb, attrs, MOUNTAIN);
        this.hillModel = mb.createSphere(1f, 1f, 1f, 12, 8, mat(HILL), attrs);
        this.cloudModel = mb.createSphere(1f, 1f, 1f, 10, 6, mat(CLOUD), attrs);
        this.treeModel = treeModel(mb, attrs);

        float s = SnapSceneGeometry.BUMP_SPACING;
        float cx = board.width() * s / 2f, cz = board.height() * s / 2f;
        float span = Math.max(board.width(), board.height()) * s;

        groundInstance = new ModelInstance(groundModel);
        groundInstance.transform.setToTranslation(cx, -4f, cz);

        ring(mountains, mountainModel, cx, cz, span * 2.2f + 1400f, 14, 600f, 1100f, 700f, 1400f, 0f, false);
        ring(hills, hillModel, cx, cz, span * 1.1f + 500f, 9, 500f, 900f, 180f, 340f, 0f, false);
        placeClouds(cx, cz, span * 1.6f + 900f, 9);

        // A dense forest ringing the board at several depths.
        for (int i = 0; i < 46; i++) {
            float ang = i * 137.5f;
            float rad = ang * MathUtils.degreesToRadians;
            float r = (115f + (i % 5) * 95f) + (i * 29 % 45);
            float x = cx + r * MathUtils.cos(rad);
            float z = cz + r * MathUtils.sin(rad);
            float scale = 0.85f + ((i * 37) % 100) / 100f * 0.95f;
            ModelInstance tree = new ModelInstance(treeModel);
            tree.transform.setToTranslation(x, -2f, z).rotate(Vector3.Y, i * 57f).scale(scale, scale, scale);
            trees.add(tree);
        }

        casters.addAll(trees); // the near trees are the shadow casters that matter around the board
        casters.addAll(hills);
    }

    private static Material mat(Color c) {
        return new Material(ColorAttribute.createDiffuse(c));
    }

    private static Model coneBaseAtZero(ModelBuilder mb, long attrs, Color color) {
        mb.begin();
        MeshPartBuilder p = mb.part("cone", GL20.GL_TRIANGLES, attrs, mat(color));
        p.setVertexTransform(new Matrix4().setToTranslation(0f, 0.5f, 0f)); // unit cone, base at y=0
        p.cone(1f, 1f, 1f, 8);
        return mb.end();
    }

    private static Model treeModel(ModelBuilder mb, long attrs) {
        mb.begin();
        MeshPartBuilder trunkP = mb.part("trunk", GL20.GL_TRIANGLES, attrs, mat(TRUNK));
        trunkP.setVertexTransform(new Matrix4().setToTranslation(0f, 10f, 0f));
        trunkP.cylinder(9f, 20f, 9f, 8);
        MeshPartBuilder folP = mb.part("foliage", GL20.GL_TRIANGLES, attrs, mat(FOLIAGE));
        folP.setVertexTransform(new Matrix4().setToTranslation(0f, 35f, 0f));
        folP.cone(42f, 32f, 42f, 8);
        folP.setVertexTransform(new Matrix4().setToTranslation(0f, 56f, 0f));
        folP.cone(28f, 24f, 28f, 8);
        return mb.end();
    }

    private void ring(List<ModelInstance> out, Model model, float cx, float cz, float radius, int count,
                      float wLo, float wHi, float hLo, float hHi, float y, boolean uniform) {
        for (int i = 0; i < count; i++) {
            float ang = i * (360f / count) + (i % 2) * 11f;
            float rad = ang * MathUtils.degreesToRadians;
            float r = radius + (i % 3) * radius * 0.12f;
            float x = cx + r * MathUtils.cos(rad);
            float z = cz + r * MathUtils.sin(rad);
            float w = wLo + ((i * 37) % 100) / 100f * (wHi - wLo);
            float h = hLo + ((i * 53) % 100) / 100f * (hHi - hLo);
            ModelInstance mi = new ModelInstance(model);
            mi.transform.setToTranslation(x, y, z).rotate(Vector3.Y, ang * 1.7f).scale(w, uniform ? w : h, w);
            out.add(mi);
        }
    }

    private void placeClouds(float cx, float cz, float radius, int count) {
        for (int i = 0; i < count; i++) {
            float ang = i * (360f / count) + 17f;
            float rad = ang * MathUtils.degreesToRadians;
            float r = radius + (i % 4) * 160f;
            float x = cx + r * MathUtils.cos(rad);
            float z = cz + r * MathUtils.sin(rad);
            float y = 520f + (i % 5) * 90f;
            float w = 200f + ((i * 41) % 100) / 100f * 260f;
            ModelInstance mi = new ModelInstance(cloudModel);
            mi.transform.setToTranslation(x, y, z).scale(w, w * 0.32f, w * 0.7f);
            clouds.add(mi);
        }
    }

    public Color skyColor() {
        return SKY_HORIZON;
    }

    /** Instances that should cast shadows in the depth pass (near trees + hills). */
    public List<ModelInstance> shadowCasters() {
        return casters;
    }

    /** Background gradient sky dome + sun glow. Call first, before the lit scene. */
    public void renderSky(Camera camera) {
        if (!sky.isCompiled()) {
            return;
        }
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        sky.bind();
        sky.setUniformMatrix("u_projViewTrans", camera.combined);
        skyWorld.setToTranslation(camera.position.x, camera.position.y, camera.position.z);
        sky.setUniformMatrix("u_worldTrans", skyWorld);
        sky.setUniformf("u_zenith", SKY_ZENITH.r, SKY_ZENITH.g, SKY_ZENITH.b);
        sky.setUniformf("u_horizon", SKY_HORIZON.r, SKY_HORIZON.g, SKY_HORIZON.b);
        sky.setUniformf("u_skyGround", SKY_GROUND.r, SKY_GROUND.g, SKY_GROUND.b);
        sky.setUniformf("u_sunDir", SUN_TO_LIGHT);
        sky.setUniformf("u_sunColor", SUN_COLOR.r, SUN_COLOR.g, SUN_COLOR.b);
        skyMesh.render(sky, GL20.GL_TRIANGLES);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
    }

    /** The lit, shadowed scenery (ground + mountains + hills + clouds + trees). */
    public void render(Camera camera) {
        modelBatch.begin(camera);
        modelBatch.render(groundInstance, environment);
        for (ModelInstance mi : mountains) modelBatch.render(mi, environment);
        for (ModelInstance mi : hills) modelBatch.render(mi, environment);
        for (ModelInstance mi : clouds) modelBatch.render(mi, environment);
        for (ModelInstance mi : trees) modelBatch.render(mi, environment);
        modelBatch.end();
    }

    private Mesh skySphere() {
        MeshBuilder mb = new MeshBuilder();
        mb.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal, GL20.GL_TRIANGLES);
        mb.sphere(6000f, 6000f, 6000f, 24, 16);
        return mb.end();
    }

    private static ShaderProgram compile(String vert, String frag, String name) {
        ShaderProgram sp = new ShaderProgram(vert, frag);
        if (!sp.isCompiled()) {
            log.error("Sky '{}' shader failed to compile: {}", name, sp.getLog());
        }
        return sp;
    }

    @Override
    public void dispose() {
        modelBatch.dispose();
        sky.dispose();
        skyMesh.dispose();
        groundModel.dispose();
        mountainModel.dispose();
        hillModel.dispose();
        cloudModel.dispose();
        treeModel.dispose();
    }
}
