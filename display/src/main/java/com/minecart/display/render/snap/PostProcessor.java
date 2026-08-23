package com.minecart.display.render.snap;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Disposable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A minimal HDR-style post-processing chain: the 3D scene is rendered into an off-screen colour buffer
 * (with a 24-bit depth buffer so the z-fight fixes survive), then a <b>bloom</b> pass (bright-pass → separable
 * Gaussian blur, at half resolution) is composited back over the scene with a gentle <b>vignette</b>. This is
 * the foundation the rest of the post effects (god rays, tonemapping, DoF) will hang off.
 *
 * <p>Usage per frame: {@link #begin()} → draw the scene → {@link #end()} → {@link #renderToScreen()}, then draw
 * the UI (which should stay crisp, so it goes on top, after the composite).
 */
public final class PostProcessor implements Disposable {

    private static final Logger log = LoggerFactory.getLogger(PostProcessor.class);

    private static final String QUAD_VERT =
            "attribute vec2 a_position;\n" +
            "attribute vec2 a_uv;\n" +
            "varying vec2 v_uv;\n" +
            "void main() { v_uv = a_uv; gl_Position = vec4(a_position, 0.0, 1.0); }\n";

    private static final String BRIGHT_FRAG =
            "#ifdef GL_ES\nprecision mediump float;\n#endif\n" +
            "varying vec2 v_uv;\n" +
            "uniform sampler2D u_texture;\n" +
            "uniform float u_threshold;\n" +
            "uniform float u_knee;\n" +
            "void main() {\n" +
            "  vec3 c = texture2D(u_texture, v_uv).rgb;\n" +
            "  float b = max(c.r, max(c.g, c.b));\n" +
            "  float soft = clamp((b - u_threshold + u_knee) / (2.0 * u_knee + 1e-4), 0.0, 1.0);\n" +
            "  float w = soft * soft;\n" +               // soft knee below threshold, full above
            "  gl_FragColor = vec4(c * w, 1.0);\n" +
            "}\n";

    private static final String BLUR_FRAG =
            "#ifdef GL_ES\nprecision mediump float;\n#endif\n" +
            "varying vec2 v_uv;\n" +
            "uniform sampler2D u_texture;\n" +
            "uniform vec2 u_dir;\n" +   // texel step along one axis
            "void main() {\n" +
            "  vec3 s = vec3(0.0);\n" +
            "  s += texture2D(u_texture, v_uv + u_dir * -4.0).rgb * 0.051;\n" +
            "  s += texture2D(u_texture, v_uv + u_dir * -3.0).rgb * 0.090;\n" +
            "  s += texture2D(u_texture, v_uv + u_dir * -2.0).rgb * 0.120;\n" +
            "  s += texture2D(u_texture, v_uv + u_dir * -1.0).rgb * 0.148;\n" +
            "  s += texture2D(u_texture, v_uv).rgb              * 0.162;\n" +
            "  s += texture2D(u_texture, v_uv + u_dir *  1.0).rgb * 0.148;\n" +
            "  s += texture2D(u_texture, v_uv + u_dir *  2.0).rgb * 0.120;\n" +
            "  s += texture2D(u_texture, v_uv + u_dir *  3.0).rgb * 0.090;\n" +
            "  s += texture2D(u_texture, v_uv + u_dir *  4.0).rgb * 0.051;\n" +
            "  gl_FragColor = vec4(s, 1.0);\n" +
            "}\n";

    // Additive bloom over the untouched scene (scene colours are already display-referred, so we don't
    // re-tonemap/gamma them here — that would shift the hand-tuned dawn palette). Bloom only adds glow.
    private static final String COMPOSITE_FRAG =
            "#ifdef GL_ES\nprecision mediump float;\n#endif\n" +
            "varying vec2 v_uv;\n" +
            "uniform sampler2D u_scene;\n" +
            "uniform sampler2D u_bloom;\n" +
            "uniform float u_bloomStrength;\n" +
            "void main() {\n" +
            "  vec3 scene = texture2D(u_scene, v_uv).rgb;\n" +
            "  vec3 bloom = texture2D(u_bloom, v_uv).rgb;\n" +
            "  vec3 col = scene + bloom * u_bloomStrength;\n" +
            "  vec2 d = v_uv - 0.5;\n" +
            "  float vig = smoothstep(1.1, 0.25, dot(d, d) * 2.2);\n" +   // soft darkening toward corners
            "  col *= mix(0.82, 1.0, vig);\n" +
            "  gl_FragColor = vec4(col, 1.0);\n" +
            "}\n";

    private final ShaderProgram brightShader;
    private final ShaderProgram blurShader;
    private final ShaderProgram compositeShader;
    private final Mesh quad;

    private FrameBuffer sceneFbo;
    private FrameBuffer brightFbo, blurFbo;
    private int width, height;

    private float threshold = 0.72f;
    private float knee = 0.35f;
    private float bloomStrength = 1.15f;
    private int blurPasses = 3;

    public PostProcessor() {
        ShaderProgram.pedantic = false;
        brightShader = compile(QUAD_VERT, BRIGHT_FRAG, "bright");
        blurShader = compile(QUAD_VERT, BLUR_FRAG, "blur");
        compositeShader = compile(QUAD_VERT, COMPOSITE_FRAG, "composite");

        float[] verts = {
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                 1f,  1f, 1f, 1f,
                -1f,  1f, 0f, 1f,
        };
        short[] idx = {0, 1, 2, 0, 2, 3};
        quad = new Mesh(true, 4, 6,
                new VertexAttribute(VertexAttributes.Usage.Position, 2, "a_position"),
                new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_uv"));
        quad.setVertices(verts);
        quad.setIndices(idx);

        resize(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    public void resize(int w, int h) {
        if (sceneFbo != null && w == width && h == height) {
            return;
        }
        dispose(sceneFbo);
        dispose(brightFbo);
        dispose(blurFbo);
        width = Math.max(1, w);
        height = Math.max(1, h);

        // Scene buffer: full-res colour + a 24-bit depth renderbuffer (libGDX's basic FrameBuffer would give
        // a 16-bit depth buffer and reintroduce the board z-fighting).
        GLFrameBuffer.FrameBufferBuilder sb = new GLFrameBuffer.FrameBufferBuilder(width, height);
        sb.addBasicColorTextureAttachment(Pixmap.Format.RGBA8888);
        sb.addDepthRenderBuffer(GL30.GL_DEPTH_COMPONENT24);
        sceneFbo = sb.build();
        linear(sceneFbo);

        int bw = Math.max(1, width / 2), bh = Math.max(1, height / 2);
        brightFbo = new FrameBuffer(Pixmap.Format.RGBA8888, bw, bh, false);
        blurFbo = new FrameBuffer(Pixmap.Format.RGBA8888, bw, bh, false);
        linear(brightFbo);
        linear(blurFbo);
    }

    /** Binds the scene buffer and clears it to {@code (r,g,b)}. Draw the 3D scene after this. */
    public void begin(float r, float g, float b) {
        sceneFbo.begin();
        Gdx.gl.glClearColor(r, g, b, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
    }

    public void end() {
        sceneFbo.end();
    }

    /** Runs bright-pass → blur → composite, writing the final image to the default framebuffer (screen). */
    public void renderToScreen() {
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(false);

        // 1) Bright-pass into the half-res bright buffer.
        brightFbo.begin();
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        brightShader.bind();
        sceneFbo.getColorBufferTexture().bind(0);
        brightShader.setUniformi("u_texture", 0);
        brightShader.setUniformf("u_threshold", threshold);
        brightShader.setUniformf("u_knee", knee);
        quad.render(brightShader, GL20.GL_TRIANGLES);
        brightFbo.end();

        // 2) Separable Gaussian blur, ping-ponging bright <-> blur.
        float tw = 1f / brightFbo.getWidth(), th = 1f / brightFbo.getHeight();
        blurShader.bind();
        FrameBuffer src = brightFbo, dst = blurFbo;
        for (int i = 0; i < blurPasses; i++) {
            // horizontal
            dst.begin();
            src.getColorBufferTexture().bind(0);
            blurShader.setUniformi("u_texture", 0);
            blurShader.setUniformf("u_dir", tw, 0f);
            quad.render(blurShader, GL20.GL_TRIANGLES);
            dst.end();
            FrameBuffer t = src; src = dst; dst = t;
            // vertical
            dst.begin();
            src.getColorBufferTexture().bind(0);
            blurShader.setUniformi("u_texture", 0);
            blurShader.setUniformf("u_dir", 0f, th);
            quad.render(blurShader, GL20.GL_TRIANGLES);
            dst.end();
            t = src; src = dst; dst = t;
        }
        FrameBuffer bloom = src; // last written

        // 3) Composite scene + bloom to the screen.
        Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        compositeShader.bind();
        sceneFbo.getColorBufferTexture().bind(0);
        bloom.getColorBufferTexture().bind(1);
        compositeShader.setUniformi("u_scene", 0);
        compositeShader.setUniformi("u_bloom", 1);
        compositeShader.setUniformf("u_bloomStrength", bloomStrength);
        quad.render(compositeShader, GL20.GL_TRIANGLES);

        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);
    }

    private static void linear(FrameBuffer fbo) {
        Texture t = fbo.getColorBufferTexture();
        t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        t.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
    }

    private static void dispose(FrameBuffer fbo) {
        if (fbo != null) {
            fbo.dispose();
        }
    }

    private static ShaderProgram compile(String v, String f, String name) {
        ShaderProgram sp = new ShaderProgram(v, f);
        if (!sp.isCompiled()) {
            log.error("Post '{}' shader failed to compile: {}", name, sp.getLog());
        }
        return sp;
    }

    @Override
    public void dispose() {
        brightShader.dispose();
        blurShader.dispose();
        compositeShader.dispose();
        quad.dispose();
        dispose(sceneFbo);
        dispose(brightFbo);
        dispose(blurFbo);
    }
}
