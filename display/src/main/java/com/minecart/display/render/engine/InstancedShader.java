package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.GdxRuntimeException;

/**
 * The part shader, in two interchangeable variants (the engine picks by GL context):
 * <ul>
 *   <li><b>Instanced (GL3.2 core, GLSL 150)</b> — Create/Flywheel-style: one base mesh drawn N times, each
 *       instance carrying its own world transform as four vec4 columns of a mat4 (per-instance vertex attributes
 *       {@code i_w0..i_w3}, fed via {@link com.badlogic.gdx.graphics.Mesh#setInstanceData}). One draw call for
 *       all instances. Used by the standalone demos (which request a core context).</li>
 *   <li><b>Uniform (GL2.0 / GLSL 120)</b> — the same mesh, but each instance's world matrix comes from a
 *       {@code u_world} uniform, one draw call per instance. Used inside the live app, whose GL2.1 context (and
 *       its GLSL-120 scene2d menus) can't be a core profile. A board has few movable/entity instances, and the
 *       static geometry is one baked mesh, so the extra draw calls are negligible.</li>
 * </ul>
 * Either way each vertex carries a baked atlas UV and the fragment stage just samples the atlas × the vertex
 * tint — <b>no lighting, no post-processing</b> (Minecraft-style fullbright; what the map holds is what renders).
 */
final class InstancedShader {

    private InstancedShader() {}

    /** Creates the variant for the current context: instanced when a GL3.0+ core context is available, else the
     *  GL2.0 uniform-per-instance variant. */
    static ShaderProgram create() {
        return create(com.badlogic.gdx.Gdx.gl30 != null);
    }

    // ---- GL2.0 / GLSL 120 uniform variant (one draw per instance, u_world uniform) ----
    private static final String VERT20 = """
            attribute vec3 a_position;
            attribute vec2 a_uv;
            attribute vec4 a_color;
            uniform mat4 u_projView;
            uniform mat4 u_world;
            varying vec2 v_uv;
            varying vec4 v_color;
            void main() {
                gl_Position = u_projView * u_world * vec4(a_position, 1.0);
                v_uv = a_uv;
                v_color = a_color;
            }
            """;

    private static final String FRAG20 = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec2 v_uv;
            varying vec4 v_color;
            uniform sampler2D u_atlas;
            void main() {
                gl_FragColor = texture2D(u_atlas, v_uv) * v_color;
            }
            """;

    private static final String VERT = """
            #version 150
            in vec3 a_position;
            in vec2 a_uv;
            in vec4 a_color;
            in vec4 i_w0;
            in vec4 i_w1;
            in vec4 i_w2;
            in vec4 i_w3;
            uniform mat4 u_projView;
            out vec2 v_uv;
            out vec4 v_color;
            void main() {
                mat4 world = mat4(i_w0, i_w1, i_w2, i_w3);
                gl_Position = u_projView * world * vec4(a_position, 1.0);
                v_uv = a_uv;
                v_color = a_color;
            }
            """;

    // The baked greyscale (or coloured) texel is MULTIPLIED by the per-vertex tint — white for normal parts,
    // the component-entity's colour for tintable parts (e.g. the LED bulb). Texel alpha carries translucency.
    private static final String FRAG = """
            #version 150
            in vec2 v_uv;
            in vec4 v_color;
            uniform sampler2D u_atlas;
            out vec4 fragColor;
            void main() {
                fragColor = texture(u_atlas, v_uv) * v_color;
            }
            """;

    static ShaderProgram create(boolean instanced) {
        ShaderProgram.pedantic = false; // instance attributes aren't referenced by name-lookup the usual way
        ShaderProgram s = instanced ? new ShaderProgram(VERT, FRAG) : new ShaderProgram(VERT20, FRAG20);
        if (!s.isCompiled()) {
            throw new GdxRuntimeException("InstancedShader (" + (instanced ? "instanced" : "uniform")
                    + ") failed to compile:\n" + s.getLog());
        }
        return s;
    }
}
