package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.GdxRuntimeException;

/**
 * The GPU-instanced part shader (Create/Flywheel-style): one base mesh drawn N times, each instance carrying
 * its own world transform as four vec4 columns of a mat4 (per-instance vertex attributes {@code i_w0..i_w3},
 * fed via {@link com.badlogic.gdx.graphics.Mesh#setInstanceData}). Vertex colours carry the baked palette
 * shade; a single directional term gives cheap form. GLSL 150 (core), required by the GL3.2 context that
 * instancing needs on macOS.
 */
final class InstancedShader {

    private InstancedShader() {}

    private static final String VERT = """
            #version 150
            in vec3 a_position;
            in vec3 a_normal;
            in vec4 a_color;
            in vec2 a_uv;
            in vec4 i_w0;
            in vec4 i_w1;
            in vec4 i_w2;
            in vec4 i_w3;
            uniform mat4 u_projView;
            out vec4 v_color;
            out vec3 v_normal;
            out vec2 v_uv;
            void main() {
                mat4 world = mat4(i_w0, i_w1, i_w2, i_w3);
                gl_Position = u_projView * world * vec4(a_position, 1.0);
                v_color = a_color;
                v_normal = normalize(mat3(world) * a_normal);
                v_uv = a_uv;
            }
            """;

    private static final String FRAG = """
            #version 150
            in vec4 v_color;
            in vec3 v_normal;
            in vec2 v_uv;
            uniform vec3 u_lightDir;
            uniform sampler2D u_dither;
            out vec4 fragColor;
            void main() {
                float ndl = max(dot(normalize(v_normal), -normalize(u_lightDir)), 0.0);
                float lit = 0.62 + 0.38 * ndl;
                float grain = texture(u_dither, v_uv).r; // quantised gray tile → per-texel plastic grain
                fragColor = vec4(v_color.rgb * lit * grain, v_color.a);
            }
            """;

    static ShaderProgram create() {
        ShaderProgram.pedantic = false; // instance attributes aren't referenced by name-lookup the usual way
        ShaderProgram s = new ShaderProgram(VERT, FRAG);
        if (!s.isCompiled()) {
            throw new GdxRuntimeException("InstancedShader failed to compile:\n" + s.getLog());
        }
        return s;
    }
}
