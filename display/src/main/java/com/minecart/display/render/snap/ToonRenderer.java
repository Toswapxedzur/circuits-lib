package com.minecart.display.render.snap;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.minecart.snap.SnapBoard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * A cartoon (cel-shaded) environment around the snap board: a low-poly ground and placeholder trees drawn
 * with a hand-written toon shader — banded {@code N·L} lighting plus a dark <b>inverted-hull outline</b>
 * (each mesh drawn once expanded along its normals with front-face culling, giving a black silhouette).
 * This is the "trees hate you" style target; drop-in CC0 {@code .glb} models can replace the procedural
 * tree later (same shader), and the colours here are a single "meadow" biome that a picker can swap.
 *
 * <p>Only a handful of meshes are drawn, so the extra outline pass and banding are essentially free.
 */
public final class ToonRenderer implements Disposable {

    private static final Logger log = LoggerFactory.getLogger(ToonRenderer.class);

    private static final String TOON_VERT =
            "attribute vec3 a_position;\n" +
            "attribute vec3 a_normal;\n" +
            "uniform mat4 u_projViewTrans;\n" +
            "uniform mat4 u_worldTrans;\n" +
            "varying vec3 v_normal;\n" +
            "void main() {\n" +
            "  mat3 m = mat3(u_worldTrans[0].xyz, u_worldTrans[1].xyz, u_worldTrans[2].xyz);\n" +
            "  v_normal = normalize(m * a_normal);\n" +
            "  gl_Position = u_projViewTrans * u_worldTrans * vec4(a_position, 1.0);\n" +
            "}\n";

    private static final String TOON_FRAG =
            "#ifdef GL_ES\nprecision mediump float;\n#endif\n" +
            "varying vec3 v_normal;\n" +
            "uniform vec3 u_lightDir;\n" +
            "uniform vec4 u_color;\n" +
            "void main() {\n" +
            "  float ndl = max(dot(normalize(v_normal), normalize(u_lightDir)), 0.0);\n" +
            "  float shade = ndl > 0.66 ? 1.0 : (ndl > 0.30 ? 0.72 : 0.48);\n" +
            "  gl_FragColor = vec4(u_color.rgb * shade, u_color.a);\n" +
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
            "void main() { gl_FragColor = vec4(0.05, 0.05, 0.08, 1.0); }\n";

    // Meadow biome palette.
    private static final Color GROUND = new Color(0.44f, 0.62f, 0.33f, 1f);
    private static final Color TRUNK = new Color(0.42f, 0.30f, 0.18f, 1f);
    private static final Color FOLIAGE = new Color(0.27f, 0.55f, 0.26f, 1f);
    private static final Color SKY = new Color(0.55f, 0.72f, 0.85f, 1f);

    private final ShaderProgram toon;
    private final ShaderProgram outline;
    private final Mesh ground;
    private final Mesh trunk;
    private final Mesh foliage;
    private final List<Matrix4> trees = new ArrayList<>();
    private final Matrix4 identity = new Matrix4();
    private final Vector3 lightDir = new Vector3(0.4f, 1f, 0.55f).nor();

    public ToonRenderer(SnapBoard board) {
        ShaderProgram.pedantic = false;
        this.toon = compile(TOON_VERT, TOON_FRAG, "toon");
        this.outline = compile(OUTLINE_VERT, OUTLINE_FRAG, "outline");

        float s = SnapSceneGeometry.BUMP_SPACING;
        float cx = board.width() * s / 2f, cz = board.height() * s / 2f;
        float span = Math.max(board.width(), board.height()) * s;

        this.ground = buildGround(cx, cz);
        this.trunk = buildTrunk();
        this.foliage = buildFoliage();

        // Ring of trees around the board (deterministic placement/variation).
        int count = 10;
        float radius = span * 0.75f + 110f;
        for (int i = 0; i < count; i++) {
            float ang = i * (360f / count);
            float r = radius + (i % 3) * 40f;
            float rad = ang * com.badlogic.gdx.math.MathUtils.degreesToRadians;
            float x = cx + r * com.badlogic.gdx.math.MathUtils.cos(rad);
            float z = cz + r * com.badlogic.gdx.math.MathUtils.sin(rad);
            float scale = 0.8f + (i % 4) * 0.18f;
            trees.add(new Matrix4().translate(x, -2f, z).rotate(Vector3.Y, ang * 1.7f).scale(scale, scale, scale));
        }
    }

    /** The biome sky colour to clear the screen with. */
    public Color skyColor() {
        return SKY;
    }

    private static ShaderProgram compile(String vert, String frag, String name) {
        ShaderProgram sp = new ShaderProgram(vert, frag);
        if (!sp.isCompiled()) {
            log.error("Toon '{}' shader failed to compile: {}", name, sp.getLog());
        }
        return sp;
    }

    private Mesh buildGround(float cx, float cz) {
        MeshBuilder mb = new MeshBuilder();
        mb.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal, GL20.GL_TRIANGLES);
        mb.setVertexTransform(new Matrix4().setToTranslation(cx, -4f, cz));
        mb.box(4000f, 4f, 4000f);
        return mb.end();
    }

    private Mesh buildTrunk() {
        MeshBuilder mb = new MeshBuilder();
        mb.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal, GL20.GL_TRIANGLES);
        mb.setVertexTransform(new Matrix4().setToTranslation(0f, 10f, 0f));
        mb.cylinder(9f, 20f, 9f, 8);
        return mb.end();
    }

    private Mesh buildFoliage() {
        MeshBuilder mb = new MeshBuilder();
        mb.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal, GL20.GL_TRIANGLES);
        mb.setVertexTransform(new Matrix4().setToTranslation(0f, 35f, 0f));
        mb.cone(40f, 30f, 40f, 8);
        mb.setVertexTransform(new Matrix4().setToTranslation(0f, 55f, 0f));
        mb.cone(26f, 22f, 26f, 8);
        return mb.end();
    }

    /** Draws the ground and trees. Manages its own GL state; leaves culling disabled for the caller. */
    public void render(Camera camera) {
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);

        // Ground: toon fill, no outline.
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        fill(camera, ground, identity, GROUND);

        // Trees: outline pass (front-face culled, expanded) then toon fill (back-face culled).
        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        for (Matrix4 world : trees) {
            outlinePass(camera, trunk, world);
            outlinePass(camera, foliage, world);
        }
        for (Matrix4 world : trees) {
            Gdx.gl.glCullFace(GL20.GL_BACK);
            fill(camera, trunk, world, TRUNK);
            fill(camera, foliage, world, FOLIAGE);
        }
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
    }

    private void fill(Camera camera, Mesh mesh, Matrix4 world, Color color) {
        toon.bind();
        toon.setUniformMatrix("u_projViewTrans", camera.combined);
        toon.setUniformMatrix("u_worldTrans", world);
        toon.setUniformf("u_lightDir", lightDir);
        toon.setUniformf("u_color", color.r, color.g, color.b, color.a);
        mesh.render(toon, GL20.GL_TRIANGLES);
    }

    private void outlinePass(Camera camera, Mesh mesh, Matrix4 world) {
        Gdx.gl.glCullFace(GL20.GL_FRONT);
        outline.bind();
        outline.setUniformMatrix("u_projViewTrans", camera.combined);
        outline.setUniformMatrix("u_worldTrans", world);
        outline.setUniformf("u_outline", 0.7f);
        mesh.render(outline, GL20.GL_TRIANGLES);
    }

    @Override
    public void dispose() {
        toon.dispose();
        outline.dispose();
        ground.dispose();
        trunk.dispose();
        foliage.dispose();
    }
}
