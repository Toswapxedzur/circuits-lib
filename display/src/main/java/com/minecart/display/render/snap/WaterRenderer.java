package com.minecart.display.render.snap;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A flat reflective pond via <b>planar reflection</b>: the scene is re-rendered from a camera mirrored across
 * the water plane into an off-screen buffer, then a custom water shader draws the pond surface sampling that
 * buffer in screen space — distorted by animated ripple normals, blended with a depth tint by a Fresnel term,
 * and topped with a sharp sun glint. This is the classic high-quality approach for a flat body of water and
 * the foundation for adding refraction/waves later.
 *
 * <p>Usage per frame: {@link #update(float)}, then {@code Camera rc = beginReflection(cam)} → draw the scene
 * with {@code rc} → {@link #endReflection()} (all before the main screen clear), then {@link #render(Camera)}
 * after the opaque scene is drawn so the terrain shoreline depth-clips the water into the basin.
 */
public final class WaterRenderer implements Disposable {

    private static final Logger log = LoggerFactory.getLogger(WaterRenderer.class);

    private static final String VERT =
            "attribute vec3 a_position;\n" +
            "uniform mat4 u_projView;\n" +
            "varying vec3 v_world;\n" +
            "varying vec4 v_clip;\n" +
            "void main() {\n" +
            "  v_world = a_position;\n" +
            "  v_clip = u_projView * vec4(a_position, 1.0);\n" +
            "  gl_Position = v_clip;\n" +
            "}\n";

    private static final String FRAG =
            "#ifdef GL_ES\nprecision highp float;\n#endif\n" +
            "varying vec3 v_world;\n" +
            "varying vec4 v_clip;\n" +
            "uniform sampler2D u_reflection;\n" +
            "uniform vec3 u_camPos;\n" +
            "uniform vec3 u_sunDir;\n" +     // direction TO the light
            "uniform vec3 u_sunColor;\n" +
            "uniform vec3 u_deep;\n" +
            "uniform vec3 u_shallow;\n" +
            "uniform float u_time;\n" +
            "vec3 rippleNormal(vec2 p, float t) {\n" +
            "  float a = sin(dot(p, vec2(0.7, 0.32)) * 0.055 + t * 1.3);\n" +
            "  float b = sin(dot(p, vec2(-0.42, 0.8)) * 0.085 - t * 1.7);\n" +
            "  float c = sin(dot(p, vec2(0.22, -0.52)) * 0.16 + t * 2.2);\n" +
            "  return normalize(vec3(a * 0.55 + c * 0.3, 4.0, b * 0.55 - c * 0.3));\n" +
            "}\n" +
            "void main() {\n" +
            "  vec2 uv = (v_clip.xy / v_clip.w) * 0.5 + 0.5;\n" +
            "  vec3 n = rippleNormal(v_world.xz, u_time);\n" +
            "  vec2 refUv = clamp(uv + n.xz * 0.035, 0.002, 0.998);\n" +
            "  vec3 reflCol = texture2D(u_reflection, refUv).rgb;\n" +
            "  vec3 viewDir = normalize(u_camPos - v_world);\n" +
            "  float cosT = clamp(dot(viewDir, n), 0.0, 1.0);\n" +
            // High reflection floor so the pond stays mirror-like even looking straight down (a calm pond
            // reflects the sky from above too) — Fresnel still pushes it fully reflective at grazing angles.
            "  float fres = 0.45 + 0.55 * pow(1.0 - cosT, 4.0);\n" +
            "  vec3 water = mix(u_deep, u_shallow, cosT);\n" +
            "  vec3 h = normalize(viewDir + normalize(u_sunDir));\n" +
            "  float spec = pow(max(dot(n, h), 0.0), 340.0);\n" +
            "  vec3 col = mix(water, reflCol * 1.1, fres) + u_sunColor * spec * 2.5;\n" +
            "  gl_FragColor = vec4(col, 1.0);\n" +
            "}\n";

    private final float waterY;
    private final Vector3 sunDir;
    private final Color sunColor;
    private final Color deep = new Color(0.05f, 0.16f, 0.21f, 1f);
    private final Color shallow = new Color(0.16f, 0.34f, 0.38f, 1f);

    private final ShaderProgram shader;
    private final Mesh quad;
    private final PerspectiveCamera reflCam = new PerspectiveCamera();
    private final Matrix4 invView = new Matrix4();

    private FrameBuffer reflectionFbo;
    private int fboW, fboH;
    private float time;

    public WaterRenderer(float waterY, float pondCx, float pondCz, float pondRadius,
                         Vector3 sunToLight, Color sunColor) {
        this.waterY = waterY;
        this.sunDir = new Vector3(sunToLight).nor();
        this.sunColor = sunColor;

        ShaderProgram.pedantic = false;
        this.shader = new ShaderProgram(VERT, FRAG);
        if (!shader.isCompiled()) {
            log.error("Water shader failed to compile: {}", shader.getLog());
        }

        float r = pondRadius * 1.2f;
        float x0 = pondCx - r, x1 = pondCx + r, z0 = pondCz - r, z1 = pondCz + r;
        float[] verts = {
                x0, waterY, z0,
                x1, waterY, z0,
                x1, waterY, z1,
                x0, waterY, z1,
        };
        short[] idx = {0, 1, 2, 0, 2, 3};
        this.quad = new Mesh(true, 4, 6, new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"));
        this.quad.setVertices(verts);
        this.quad.setIndices(idx);

        resize(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    public void update(float dt) {
        time += dt;
    }

    public void resize(int width, int height) {
        if (reflectionFbo != null && width == fboW && height == fboH) {
            return;
        }
        if (reflectionFbo != null) {
            reflectionFbo.dispose();
        }
        fboW = Math.max(1, width);
        fboH = Math.max(1, height);
        reflectionFbo = new FrameBuffer(Pixmap.Format.RGBA8888, fboW, fboH, true);
        Texture t = reflectionFbo.getColorBufferTexture();
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        t.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
    }

    /**
     * Binds the reflection buffer and returns a camera mirrored across the water plane. Draw the scene (sky,
     * terrain, trees) with the returned camera, then call {@link #endReflection()}. Winding is flipped while
     * the buffer is bound so back-face-culled geometry stays correct in the mirror.
     */
    public Camera beginReflection(PerspectiveCamera main, Color clearColor) {
        reflCam.fieldOfView = main.fieldOfView;
        reflCam.viewportWidth = main.viewportWidth;
        reflCam.viewportHeight = main.viewportHeight;
        reflCam.near = main.near;
        reflCam.far = main.far;
        reflCam.position.set(main.position.x, 2f * waterY - main.position.y, main.position.z);
        reflCam.direction.set(main.direction.x, -main.direction.y, main.direction.z).nor();
        reflCam.up.set(main.up.x, -main.up.y, main.up.z).nor();
        reflCam.update();
        // Oblique near-plane clip at the water surface: without it, from a high camera the mirror camera
        // drops far below the terrain and its upward view is blocked by the pond's own basin floor, filling
        // the reflection with dark ground. Clipping everything below the surface removes that occluder.
        applyObliqueClip(reflCam, waterY - 1f);

        reflectionFbo.begin();
        Gdx.gl.glClearColor(clearColor.r, clearColor.g, clearColor.b, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glFrontFace(GL20.GL_CW); // mirrored geometry: flip winding so culling stays consistent
        return reflCam;
    }

    public void endReflection() {
        Gdx.gl.glFrontFace(GL20.GL_CCW);
        reflectionFbo.end();
    }

    /**
     * Lengyel's oblique near-plane clipping: retilts {@code cam}'s projection so its near plane coincides
     * with the world plane {@code y = planeY}, clipping everything below it. Recomputes {@code cam.combined}
     * so the reflection render (which uses it) picks up the clip. Call after {@link PerspectiveCamera#update()}.
     */
    private void applyObliqueClip(PerspectiveCamera cam, float planeY) {
        // World clip plane keeping the half-space above the water: dot((0,1,0,-planeY), (x,y,z,1)) = y - planeY.
        float cwx = 0f, cwy = 1f, cwz = 0f, cww = -planeY;
        // Transform the plane into view space: clipView = (view^-1)^T * clipWorld.
        invView.set(cam.view).inv();
        float[] v = invView.val; // column-major
        float px = v[0] * cwx + v[1] * cwy + v[2] * cwz + v[3] * cww;
        float py = v[4] * cwx + v[5] * cwy + v[6] * cwz + v[7] * cww;
        float pz = v[8] * cwx + v[9] * cwy + v[10] * cwz + v[11] * cww;
        float pw = v[12] * cwx + v[13] * cwy + v[14] * cwz + v[15] * cww;
        float len = (float) Math.sqrt(px * px + py * py + pz * pz);
        if (len < 1e-6f) {
            return;
        }
        px /= len; py /= len; pz /= len; pw /= len;

        float[] p = cam.projection.val; // column-major; indices per libGDX Matrix4 layout
        float qx = (Math.signum(px) + p[8]) / p[0];
        float qy = (Math.signum(py) + p[9]) / p[5];
        float qz = -1f;
        float qw = (1f + p[10]) / p[14];
        float dot = px * qx + py * qy + pz * qz + pw * qw;
        float a = 2f / dot;
        p[2] = px * a;          // M20
        p[6] = py * a;          // M21
        p[10] = pz * a + 1f;    // M22
        p[14] = pw * a;         // M23
        cam.combined.set(cam.projection).mul(cam.view);
    }

    /** Draws the pond surface. Call after the opaque scene so the terrain shoreline depth-clips the water. */
    public void render(Camera camera) {
        if (!shader.isCompiled()) {
            return;
        }
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);
        reflectionFbo.getColorBufferTexture().bind(0);
        shader.bind();
        shader.setUniformi("u_reflection", 0);
        shader.setUniformMatrix("u_projView", camera.combined);
        shader.setUniformf("u_camPos", camera.position);
        shader.setUniformf("u_sunDir", sunDir);
        shader.setUniformf("u_sunColor", sunColor.r, sunColor.g, sunColor.b);
        shader.setUniformf("u_deep", deep.r, deep.g, deep.b);
        shader.setUniformf("u_shallow", shallow.r, shallow.g, shallow.b);
        shader.setUniformf("u_time", time);
        quad.render(shader, GL20.GL_TRIANGLES);
    }

    @Override
    public void dispose() {
        shader.dispose();
        quad.dispose();
        if (reflectionFbo != null) {
            reflectionFbo.dispose();
        }
    }
}
