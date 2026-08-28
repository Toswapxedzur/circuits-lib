package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.GdxRuntimeException;

/**
 * The GPU-instanced part shader (Create/Flywheel-style): one base mesh drawn N times, each instance carrying
 * its own world transform as four vec4 columns of a mat4 (per-instance vertex attributes {@code i_w0..i_w3},
 * fed via {@link com.badlogic.gdx.graphics.Mesh#setInstanceData}). Each vertex carries a baked atlas UV; the
 * fragment stage just samples the atlas — <b>no lighting, no post-processing</b>. What the map holds is what
 * renders (Minecraft-style, fullbright). GLSL 150 (core), required by the GL3.2 context instancing needs on
 * macOS.
 */
final class InstancedShader {

    private InstancedShader() {}

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

    static ShaderProgram create() {
        ShaderProgram.pedantic = false; // instance attributes aren't referenced by name-lookup the usual way
        ShaderProgram s = new ShaderProgram(VERT, FRAG);
        if (!s.isCompiled()) {
            throw new GdxRuntimeException("InstancedShader failed to compile:\n" + s.getLog());
        }
        return s;
    }
}
