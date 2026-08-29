package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.GdxRuntimeException;

/**
 * The part shader, in two interchangeable variants (the engine picks by GL context):
 * <ul>
 *   <li><b>Instanced (GL3.2 core, GLSL 150)</b> — one base mesh drawn N times, world transform from per-instance
 *       attributes {@code i_w0..i_w3}. Used by the standalone demos (core context).</li>
 *   <li><b>Uniform (GL2.0 / GLSL 120)</b> — the same mesh, world matrix from a {@code u_world} uniform, one draw
 *       per instance. Used inside the live app (GL2.1 context, GLSL-120 scene2d menus).</li>
 * </ul>
 * <b>Lighting</b> (Milestone 5): the baked texel (albedo) × vertex tint is shaded by a moderate <b>ambient</b> +
 * a soft <b>directional</b> key light (which casts a {@link ShadowMap shadow}), plus up to {@link #MAX_LIGHTS}
 * <b>point lights</b> (LEDs / glowing entities emit these). World-space position + normal come from the vertex
 * stage. The two GLSL versions share one fragment body via {@code #define} token swaps.
 */
final class InstancedShader {

    private InstancedShader() {}

    /** Max simultaneous point lights the shader loops over. */
    static final int MAX_LIGHTS = 16;

    static ShaderProgram create() {
        return create(com.badlogic.gdx.Gdx.gl30 != null);
    }

    // ---- vertex stages ----
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

    // ---- shared fragment body (written GL120-style: `varying`, `texture2D`, `gl_FragColor`; the GL150 build
    //      swaps those tokens). Uses v_uv/v_color/v_normal/v_worldPos from the vertex stage. ----
    private static final String FRAG_BODY = """
            varying vec2 v_uv;
            varying vec4 v_color;
            varying vec3 v_normal;
            varying vec3 v_worldPos;
            uniform sampler2D u_atlas;
            uniform vec3 u_ambient;
            uniform vec3 u_lightDir;
            uniform int u_numLights;
            uniform vec3 u_lightPos[NUM_LIGHTS];
            uniform vec3 u_lightColor2[NUM_LIGHTS];
            uniform float u_lightRange[NUM_LIGHTS];
            uniform sampler2D u_shadowMap;    // GL3.0: hardware DEPTH texture (.r). GL2.0: RGBA-packed depth.
            uniform mat4 u_lightViewProj;
            uniform float u_shadowStrength;   // 0 = shadows off (map/depth still fine); 1 = full
            uniform float u_shadowBias;       // depth-compare bias, scaled to the shadow texel world size
            uniform float u_shadowTexel;      // 1.0 / shadow map size, for PCF tap spacing
            // GL2.0 path only: reverse DepthShader.packDepth (a [0,1] depth packed into RGBA8).
            float unpackDepth(vec4 c) {
                return dot(c, vec4(1.0, 1.0/255.0, 1.0/65025.0, 1.0/16581375.0));
            }
            // Shading takes the shadow factor as a PARAMETER — the shadow map is sampled in main(), not here.
            // (A driver quirk: the same sample reads black when done inside this function but works in main(); keep
            // all sampling in main() to sidestep it.)
            vec3 shade(vec3 N, vec3 worldPos, float sf) {
                // The SKYLIGHT directional is BAKED into the texel (per-octant, see PaletteDither) — the shader
                // just passes that base through (u_ambient ~ 1.0) and STACKS user point lights on top. sf is the
                // shadow factor: it darkens the baked base in shadowed areas (demo GL3.2 path only).
                vec3 lit = u_ambient * sf;
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
            void main() {
                vec4 texel = texture2D(u_atlas, v_uv) * v_color;
                vec3 N = normalize(v_normal);
                float ndl = max(dot(N, u_lightDir), 0.0);
                float sf = 1.0;
                if (u_shadowStrength >= 0.5) {
                    vec4 lp = u_lightViewProj * vec4(v_worldPos, 1.0);
                    vec3 ndc = lp.xyz / lp.w;
                    vec2 suv = ndc.xy * 0.5 + 0.5;
                    float fragDepth = ndc.z * 0.5 + 0.5;
                    if (suv.x >= 0.0 && suv.x <= 1.0 && suv.y >= 0.0 && suv.y <= 1.0 && fragDepth <= 1.0) {
                        float bias = u_shadowBias * (2.0 - ndl);
                        float lit = 0.0;                       // 3x3 PCF
                        for (int x = -1; x <= 1; x++) {
                            for (int y = -1; y <= 1; y++) {
                                vec2 off = vec2(float(x), float(y)) * u_shadowTexel;
                                #ifdef PACKED_SHADOW
                                float stored = unpackDepth(texture2D(u_shadowMap, suv + off));
                                #else
                                float stored = texture2D(u_shadowMap, suv + off).r;
                                #endif
                                lit += (fragDepth - bias > stored) ? 0.0 : 1.0;
                            }
                        }
                        sf = 0.45 + 0.55 * (lit / 9.0);        // 0.45 in full shadow → 1.0 fully lit
                    }
                }
                gl_FragColor = vec4(texel.rgb * shade(N, v_worldPos, sf), texel.a);
            }
            """;

    private static String frag20() {
        // highp for the RGBA depth-unpack precision; PACKED_SHADOW selects the unpack branch (GL2.0 has no depth tex).
        return "#ifdef GL_ES\nprecision highp float;\n#endif\n#define NUM_LIGHTS " + MAX_LIGHTS
                + "\n#define PACKED_SHADOW\n" + FRAG_BODY;
    }

    private static String frag150() {
        String body = ("#version 150\n#define NUM_LIGHTS " + MAX_LIGHTS + "\nout vec4 v_fragColor;\n" + FRAG_BODY)
                .replace("varying ", "in ")
                .replace("texture2D", "texture")
                .replace("gl_FragColor", "v_fragColor");
        return body;
    }

    static ShaderProgram create(boolean instanced) {
        ShaderProgram.pedantic = false; // instance / unused-light-slot uniforms aren't name-looked-up the usual way
        ShaderProgram s = instanced ? new ShaderProgram(VERT, frag150()) : new ShaderProgram(VERT20, frag20());
        if (!s.isCompiled()) {
            throw new GdxRuntimeException("InstancedShader (" + (instanced ? "instanced" : "uniform")
                    + ") failed to compile:\n" + s.getLog());
        }
        return s;
    }
}
