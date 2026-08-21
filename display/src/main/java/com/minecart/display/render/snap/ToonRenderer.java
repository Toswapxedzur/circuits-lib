package com.minecart.display.render.snap;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
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
 * A dramatic, non-navigable stylized backdrop that surrounds the snap board so building circuits feels
 * cinematic. It is pure ambience — the player stays near the board; this just makes the world beautiful.
 *
 * <h2>Look</h2>
 * <ul>
 *   <li><b>Gradient sky dome</b> (deep-indigo zenith → warm sunset horizon) with a glowing <b>sun disc + halo</b>.</li>
 *   <li><b>Cel lighting with warm/cool banding</b> — lit faces tinted by the warm sun, shadowed faces by the
 *       cool sky — plus a warm <b>rim/backlight</b> for glowing silhouettes.</li>
 *   <li><b>Atmospheric fog</b> that fades distant geometry into the horizon haze (aerial perspective).</li>
 *   <li>Layered scenery: distant <b>mountains</b>, mid <b>hills</b>, low-poly <b>trees</b> (with inverted-hull
 *       outlines), and drifting <b>clouds</b>.</li>
 * </ul>
 * Everything is a handful of procedural {@link MeshBuilder} meshes drawn with two hand-written shaders, so
 * it stays cheap. CC0 {@code .glb} models can later replace the placeholders through the same shader.
 */
public final class ToonRenderer implements Disposable {

    private static final Logger log = LoggerFactory.getLogger(ToonRenderer.class);

    private static final String SCENE_VERT =
            "attribute vec3 a_position;\n" +
            "attribute vec3 a_normal;\n" +
            "uniform mat4 u_projViewTrans;\n" +
            "uniform mat4 u_worldTrans;\n" +
            "varying vec3 v_normal;\n" +
            "varying vec3 v_worldPos;\n" +
            "void main() {\n" +
            "  mat3 m = mat3(u_worldTrans[0].xyz, u_worldTrans[1].xyz, u_worldTrans[2].xyz);\n" +
            "  v_normal = normalize(m * a_normal);\n" +
            "  vec4 wp = u_worldTrans * vec4(a_position, 1.0);\n" +
            "  v_worldPos = wp.xyz;\n" +
            "  gl_Position = u_projViewTrans * wp;\n" +
            "}\n";

    private static final String SCENE_FRAG =
            "#ifdef GL_ES\nprecision mediump float;\n#endif\n" +
            "varying vec3 v_normal;\n" +
            "varying vec3 v_worldPos;\n" +
            "uniform vec3 u_lightDir;\n" +
            "uniform vec4 u_color;\n" +
            "uniform vec3 u_sunColor;\n" +
            "uniform vec3 u_skyAmbient;\n" +
            "uniform vec3 u_rimColor;\n" +
            "uniform vec3 u_camPos;\n" +
            "uniform vec3 u_fogColor;\n" +
            "uniform float u_fogDensity;\n" +
            "void main() {\n" +
            "  vec3 N = normalize(v_normal);\n" +
            "  vec3 V = normalize(u_camPos - v_worldPos);\n" +
            "  float ndl = max(dot(N, normalize(u_lightDir)), 0.0);\n" +
            "  float band = ndl > 0.66 ? 1.0 : (ndl > 0.30 ? 0.6 : 0.22);\n" +
            "  vec3 lit = u_color.rgb * u_sunColor * 1.15;\n" +
            "  vec3 sha = u_color.rgb * u_skyAmbient;\n" +
            "  vec3 col = mix(sha, lit, band);\n" +
            "  float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0);\n" +
            "  col += u_rimColor * rim * (0.35 + 0.65 * ndl);\n" +
            "  float dist = length(u_camPos - v_worldPos);\n" +
            "  float fog = 1.0 - exp(-u_fogDensity * dist);\n" +
            "  col = mix(col, u_fogColor, clamp(fog, 0.0, 1.0));\n" +
            "  gl_FragColor = vec4(col, u_color.a);\n" +
            "}\n";

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

    private static final String OUTLINE_VERT =
            "attribute vec3 a_position;\n" +
            "attribute vec3 a_normal;\n" +
            "uniform mat4 u_projViewTrans;\n" +
            "uniform mat4 u_worldTrans;\n" +
            "uniform float u_outline;\n" +
            "void main() {\n" +
            "  vec3 p = a_position + a_normal * u_outline;\n" +
            "  gl_Position = u_projViewTrans * u_worldTrans * vec4(p, 1.0);\n" +
            "}\n";

    private static final String OUTLINE_FRAG =
            "#ifdef GL_ES\nprecision mediump float;\n#endif\n" +
            "void main() { gl_FragColor = vec4(0.06, 0.04, 0.10, 1.0); }\n";

    // --- Sunset palette ---
    private static final Color SKY_ZENITH = new Color(0.12f, 0.11f, 0.30f, 1f);
    private static final Color SKY_HORIZON = new Color(0.98f, 0.58f, 0.36f, 1f);
    private static final Color SKY_GROUND = new Color(0.14f, 0.09f, 0.16f, 1f);
    private static final Color SUN_COLOR = new Color(1.0f, 0.86f, 0.62f, 1f);
    private static final Color SKY_AMBIENT = new Color(0.34f, 0.40f, 0.58f, 1f);
    private static final Color RIM_COLOR = new Color(1.0f, 0.62f, 0.34f, 1f);
    private static final Color FOG_COLOR = new Color(0.92f, 0.62f, 0.46f, 1f);
    private static final float FOG_DENSITY = 0.00085f;

    private static final Color GROUND = new Color(0.34f, 0.46f, 0.28f, 1f);
    private static final Color MOUNTAIN = new Color(0.30f, 0.27f, 0.42f, 1f);
    private static final Color HILL = new Color(0.31f, 0.45f, 0.30f, 1f);
    private static final Color TRUNK = new Color(0.40f, 0.27f, 0.16f, 1f);
    private static final Color FOLIAGE = new Color(0.19f, 0.42f, 0.22f, 1f);
    private static final Color CLOUD = new Color(1.0f, 0.82f, 0.74f, 1f);

    private final Vector3 lightDir = new Vector3(0.55f, 0.26f, 0.45f).nor();

    private final ShaderProgram scene;
    private final ShaderProgram sky;
    private final ShaderProgram outline;

    private final Mesh skyMesh;
    private final Mesh ground;
    private final Mesh mountainMesh;
    private final Mesh hillMesh;
    private final Mesh cloudMesh;
    private final Mesh trunk;
    private final Mesh foliage;

    private final List<Matrix4> mountains = new ArrayList<>();
    private final List<Matrix4> hills = new ArrayList<>();
    private final List<Matrix4> clouds = new ArrayList<>();
    private final List<Matrix4> trees = new ArrayList<>();
    private final Matrix4 identity = new Matrix4();
    private final Matrix4 skyWorld = new Matrix4();

    public ToonRenderer(SnapBoard board) {
        ShaderProgram.pedantic = false;
        this.scene = compile(SCENE_VERT, SCENE_FRAG, "scene");
        this.sky = compile(SKY_VERT, SKY_FRAG, "sky");
        this.outline = compile(OUTLINE_VERT, OUTLINE_FRAG, "outline");

        float s = SnapSceneGeometry.BUMP_SPACING;
        float cx = board.width() * s / 2f, cz = board.height() * s / 2f;
        float span = Math.max(board.width(), board.height()) * s;

        this.skyMesh = sphere(6000f, 6000f, 6000f, 24, 16);
        this.ground = box(cx, -4f, cz, 8000f, 4f, 8000f);
        this.mountainMesh = cone(0f, 0f, 0f, 1f, 1f, 1f, 6); // unit cone, scaled per instance
        this.hillMesh = sphere(1f, 1f, 1f, 12, 8);           // unit sphere, scaled per instance
        this.cloudMesh = sphere(1f, 1f, 1f, 10, 6);
        this.trunk = cylinderAt(0f, 10f, 0f, 9f, 20f, 8);
        this.foliage = twoConesFoliage();

        placeRing(mountains, cx, cz, span * 2.2f + 1400f, 14, 1.7f,
                600f, 1100f, 700f, 1400f, 0f);   // far, huge, on the ground
        placeRing(hills, cx, cz, span * 1.1f + 500f, 7, 2.3f,
                500f, 900f, 180f, 340f, 0f);     // mid rolling hills (half-buried spheres)
        placeRing(trees, cx, cz, span * 0.75f + 130f, 16, 1.3f,
                0.8f, 1.5f, 0f, 0f, -2f);        // near trees (scale range in width slot)
        placeClouds(clouds, cx, cz, span * 1.6f + 900f, 9);
    }

    /** Deterministic ring placement; wLo/wHi is width (or tree scale), hLo/hHi is height, y is base. */
    private void placeRing(List<Matrix4> out, float cx, float cz, float radius, int count, float spin,
                           float wLo, float wHi, float hLo, float hHi, float y) {
        for (int i = 0; i < count; i++) {
            float ang = i * (360f / count) + (i % 2) * 11f;
            float rad = ang * MathUtils.degreesToRadians;
            float r = radius + (i % 3) * radius * 0.12f;
            float x = cx + r * MathUtils.cos(rad);
            float z = cz + r * MathUtils.sin(rad);
            float w = wLo + ((i * 37) % 100) / 100f * (wHi - wLo);
            float h = hLo + ((i * 53) % 100) / 100f * (hHi - hLo);
            Matrix4 m = new Matrix4().translate(x, y, z).rotate(Vector3.Y, ang * spin);
            if (hHi > 0f) {
                m.scale(w, h, w); // mountains/hills: distinct width & height
            } else {
                m.scale(w, w, w); // trees: uniform scale from the width slot
            }
            out.add(m);
        }
    }

    private void placeClouds(List<Matrix4> out, float cx, float cz, float radius, int count) {
        for (int i = 0; i < count; i++) {
            float ang = i * (360f / count) + 17f;
            float rad = ang * MathUtils.degreesToRadians;
            float r = radius + (i % 4) * 160f;
            float x = cx + r * MathUtils.cos(rad);
            float z = cz + r * MathUtils.sin(rad);
            float y = 520f + (i % 5) * 90f;
            float w = 200f + ((i * 41) % 100) / 100f * 260f;
            out.add(new Matrix4().translate(x, y, z).scale(w, w * 0.32f, w * 0.7f));
        }
    }

    /** The horizon colour, used as the window clear (the sky dome paints over it anyway). */
    public Color skyColor() {
        return SKY_HORIZON;
    }

    // --- rendering ---

    public void render(Camera camera) {
        if (!scene.isCompiled()) {
            return;
        }

        // Sky dome: no depth, no cull, centred on the camera.
        if (sky.isCompiled()) {
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
            sky.setUniformf("u_sunDir", lightDir);
            sky.setUniformf("u_sunColor", SUN_COLOR.r, SUN_COLOR.g, SUN_COLOR.b);
            skyMesh.render(sky, GL20.GL_TRIANGLES);
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        }

        // Opaque scenery.
        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        Gdx.gl.glCullFace(GL20.GL_BACK);
        beginScene(camera);
        fill(ground, identity, GROUND);
        for (Matrix4 m : mountains) fill(mountainMesh, m, MOUNTAIN);
        for (Matrix4 m : hills) fill(hillMesh, m, HILL);
        for (Matrix4 m : clouds) fill(cloudMesh, m, CLOUD);

        // Trees: outline pass then toon fill.
        if (outline.isCompiled()) {
            Gdx.gl.glCullFace(GL20.GL_FRONT);
            outline.bind();
            outline.setUniformMatrix("u_projViewTrans", camera.combined);
            for (Matrix4 m : trees) {
                outline.setUniformMatrix("u_worldTrans", m);
                outline.setUniformf("u_outline", 0.9f);
                trunk.render(outline, GL20.GL_TRIANGLES);
                foliage.render(outline, GL20.GL_TRIANGLES);
            }
            Gdx.gl.glCullFace(GL20.GL_BACK);
        }
        beginScene(camera);
        for (Matrix4 m : trees) {
            fill(trunk, m, TRUNK);
            fill(foliage, m, FOLIAGE);
        }
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
    }

    private void beginScene(Camera camera) {
        scene.bind();
        scene.setUniformMatrix("u_projViewTrans", camera.combined);
        scene.setUniformf("u_lightDir", lightDir);
        scene.setUniformf("u_sunColor", SUN_COLOR.r, SUN_COLOR.g, SUN_COLOR.b);
        scene.setUniformf("u_skyAmbient", SKY_AMBIENT.r, SKY_AMBIENT.g, SKY_AMBIENT.b);
        scene.setUniformf("u_rimColor", RIM_COLOR.r, RIM_COLOR.g, RIM_COLOR.b);
        scene.setUniformf("u_camPos", camera.position);
        scene.setUniformf("u_fogColor", FOG_COLOR.r, FOG_COLOR.g, FOG_COLOR.b);
        scene.setUniformf("u_fogDensity", FOG_DENSITY);
    }

    private void fill(Mesh mesh, Matrix4 world, Color color) {
        scene.setUniformMatrix("u_worldTrans", world);
        scene.setUniformf("u_color", color.r, color.g, color.b, color.a);
        mesh.render(scene, GL20.GL_TRIANGLES);
    }

    // --- mesh builders ---

    private static Mesh box(float x, float y, float z, float w, float h, float d) {
        MeshBuilder mb = new MeshBuilder();
        mb.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal, GL20.GL_TRIANGLES);
        mb.setVertexTransform(new Matrix4().setToTranslation(x, y, z));
        mb.box(w, h, d);
        return mb.end();
    }

    private static Mesh sphere(float w, float h, float d, int u, int v) {
        MeshBuilder mb = new MeshBuilder();
        mb.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal, GL20.GL_TRIANGLES);
        mb.sphere(w, h, d, u, v);
        return mb.end();
    }

    private static Mesh cone(float x, float y, float z, float w, float h, float d, int div) {
        MeshBuilder mb = new MeshBuilder();
        mb.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal, GL20.GL_TRIANGLES);
        mb.setVertexTransform(new Matrix4().setToTranslation(x, y + 0.5f, z)); // base at y=0 for a unit cone
        mb.cone(w, h, d, div);
        return mb.end();
    }

    private static Mesh cylinderAt(float x, float y, float z, float diameter, float height, int div) {
        MeshBuilder mb = new MeshBuilder();
        mb.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal, GL20.GL_TRIANGLES);
        mb.setVertexTransform(new Matrix4().setToTranslation(x, y, z));
        mb.cylinder(diameter, height, diameter, div);
        return mb.end();
    }

    private static Mesh twoConesFoliage() {
        MeshBuilder mb = new MeshBuilder();
        mb.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal, GL20.GL_TRIANGLES);
        mb.setVertexTransform(new Matrix4().setToTranslation(0f, 35f, 0f));
        mb.cone(42f, 32f, 42f, 8);
        mb.setVertexTransform(new Matrix4().setToTranslation(0f, 56f, 0f));
        mb.cone(28f, 24f, 28f, 8);
        return mb.end();
    }

    private static ShaderProgram compile(String vert, String frag, String name) {
        ShaderProgram sp = new ShaderProgram(vert, frag);
        if (!sp.isCompiled()) {
            log.error("Toon '{}' shader failed to compile: {}", name, sp.getLog());
        }
        return sp;
    }

    @Override
    public void dispose() {
        scene.dispose();
        sky.dispose();
        outline.dispose();
        skyMesh.dispose();
        ground.dispose();
        mountainMesh.dispose();
        hillMesh.dispose();
        cloudMesh.dispose();
        trunk.dispose();
        foliage.dispose();
    }
}
