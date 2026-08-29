package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.GdxRuntimeException;

/**
 * The part shader, in two interchangeable variants (the engine picks by GL context):
 * <ul>
 *   <li><b>Instanced (GL3.2 core, GLSL 150)</b> — one base mesh drawn N times, each instance carrying its own
 *       world transform as four vec4 columns of a mat4 (per-instance attributes {@code i_w0..i_w3}). One draw
 *       call. Used by the standalone demos (core context).</li>
 *   <li><b>Uniform (GL2.0 / GLSL 120)</b> — the same mesh, world matrix from a {@code u_world} uniform, one draw
 *       per instance. Used inside the live app (GL2.1 context, GLSL-120 scene2d menus).</li>
 * </ul>
 * <b>Lighting</b> (Milestone 5): the baked texel × vertex tint is shaded by a moderate <b>ambient</b> + a soft
 * <b>directional</b> light, plus up to {@link #MAX_LIGHTS} <b>point lights</b> (LEDs emit these). World-space
 * position + normal come from the vertex stage. It is NOT the flat fullbright of before; the map's texels are the
 * albedo, the shader lights them.
 */
final class InstancedShader {

    private InstancedShader() {}

    /** Max simultaneous point lights the shader loops over (LEDs etc.). */
    static final int MAX_LIGHTS = 16;

    /** Creates the variant for the current context: instanced when a GL3.0+ core context is available. */
    static ShaderProgram create() {
        return create(com.badlogic.gdx.Gdx.gl30 != null);
    }

    // Shared lighting math (GLSL-version-agnostic body). ndl from the directional light + attenuated point lights.
    private static final String LIGHT_FN = """
            vec3 shade(vec3 N, vec3 worldPos) {
                vec3 lit = u_ambient + u_lightColor * max(dot(N, u_lightDir), 0.0);
                for (int i = 0; i < NUM_LIGHTS; i++) {
                    if (i >= u_numLights) break;
                    vec3 d = u_lightPos[i] - worldPos;
                    float dist = length(d);
                    float r = u_lightRange[i];
                    float atten = clamp(1.0 - (dist * dist) / (r * r), 0.0, 1.0);
                    atten *= atten;
                    lit += u_lightColor2[i] * (max(dot(N, d / max(dist, 0.0001)), 0.0) * atten);
                }
                return lit;
            }
            """;

    private static String uniformsGlsl(boolean glsl150) {
        return (glsl150 ? "" : "")
                + "uniform sampler2D u_atlas;\n"
                + "uniform vec3 u_ambient;\n"
                + "uniform vec3 u_lightDir;\n"      // direction TO the directional light (normalized)
                + "uniform vec3 u_lightColor;\n"
                + "uniform int u_numLights;\n"
                + "uniform vec3 u_lightPos[NUM_LIGHTS];\n"
                + "uniform vec3 u_lightColor2[NUM_LIGHTS];\n"
                + "uniform float u_lightRange[NUM_LIGHTS];\n";
    }

    // ---- GL2.0 / GLSL 120 uniform variant ----
    private static final String VERT20 = """
            attribute vec3 a_position;
            attribute vec3 a_normal;
            attribute vec2 a_uv;
            attribute vec4 a_color;
            uniform mat4 u_projView;
            uniform mat4 u_world;
            varying vec2 v_uv;
            varying vec4 v_color;
            varying vec3 v_normal;
            varying vec3 v_worldPos;
            void main() {
                vec4 wp = u_world * vec4(a_position, 1.0);
                gl_Position = u_projView * wp;
                v_uv = a_uv;
                v_color = a_color;
                v_normal = (u_world * vec4(a_normal, 0.0)).xyz;
                v_worldPos = wp.xyz;
            }
            """;

    private static String frag20() {
        return "#ifdef GL_ES\nprecision mediump float;\n#endif\n"
                + "#define NUM_LIGHTS " + MAX_LIGHTS + "\n"
                + "varying vec2 v_uv;\n"
                + "varying vec4 v_color;\n"
                + "varying vec3 v_normal;\n"
                + "varying vec3 v_worldPos;\n"
                + uniformsGlsl(false)
                + LIGHT_FN
                + "void main() {\n"
                + "    vec4 texel = texture2D(u_atlas, v_uv) * v_color;\n"
                + "    vec3 lit = shade(normalize(v_normal), v_worldPos);\n"
                + "    gl_FragColor = vec4(texel.rgb * lit, texel.a);\n"
                + "}\n";
    }

    // ---- GL3.2 core / GLSL 150 instanced variant ----
    private static final String VERT = """
            #version 150
            in vec3 a_position;
            in vec3 a_normal;
            in vec2 a_uv;
            in vec4 a_color;
            in vec4 i_w0;
            in vec4 i_w1;
            in vec4 i_w2;
            in vec4 i_w3;
            uniform mat4 u_projView;
            out vec2 v_uv;
            out vec4 v_color;
            out vec3 v_normal;
            out vec3 v_worldPos;
            void main() {
                mat4 world = mat4(i_w0, i_w1, i_w2, i_w3);
                vec4 wp = world * vec4(a_position, 1.0);
                gl_Position = u_projView * wp;
                v_uv = a_uv;
                v_color = a_color;
                v_normal = (world * vec4(a_normal, 0.0)).xyz;
                v_worldPos = wp.xyz;
            }
            """;

    private static String frag150() {
        return "#version 150\n"
                + "#define NUM_LIGHTS " + MAX_LIGHTS + "\n"
                + "in vec2 v_uv;\n"
                + "in vec4 v_color;\n"
                + "in vec3 v_normal;\n"
                + "in vec3 v_worldPos;\n"
                + "out vec4 fragColor;\n"
                + uniformsGlsl(true).replace("texture2D", "texture")
                + LIGHT_FN
                + "void main() {\n"
                + "    vec4 texel = texture(u_atlas, v_uv) * v_color;\n"
                + "    vec3 lit = shade(normalize(v_normal), v_worldPos);\n"
                + "    fragColor = vec4(texel.rgb * lit, texel.a);\n"
                + "}\n";
    }

    static ShaderProgram create(boolean instanced) {
        ShaderProgram.pedantic = false; // instance attributes / unused light slots aren't name-looked-up the usual way
        ShaderProgram s = instanced ? new ShaderProgram(VERT, frag150()) : new ShaderProgram(VERT20, frag20());
        if (!s.isCompiled()) {
            throw new GdxRuntimeException("InstancedShader (" + (instanced ? "instanced" : "uniform")
                    + ") failed to compile:\n" + s.getLog());
        }
        return s;
    }
}
