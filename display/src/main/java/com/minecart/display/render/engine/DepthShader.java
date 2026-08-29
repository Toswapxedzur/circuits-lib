package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.GdxRuntimeException;

/**
 * The <b>shadow-map depth</b> shader: renders the scene from the key light's orthographic point of view into a
 * hardware <b>depth texture</b> (see {@link ShadowMap}). The GPU writes {@code gl_FragCoord.z} into the depth
 * attachment automatically, so this shader only needs to transform position — the fragment stage writes nothing
 * meaningful. {@link EngineRenderer}'s main shader samples the depth texture's {@code .r} and compares. Two
 * variants matching {@link InstancedShader}: instanced (GL3.2, per-instance {@code i_w0..i_w3}) and uniform
 * (GL2.0, {@code u_world}).
 *
 * <p><b>Attribute parity matters:</b> the part {@link PartMesh mesh} carries four vertex attributes
 * ({@code a_position, a_normal, a_uv, a_color}). The depth pass and the lit main pass render the SAME instanced
 * mesh, switching shaders between them; in a GL3 core VAO, a shader that omits some of the mesh's attributes
 * leaves the others' arrays in an inconsistent enabled state → {@code GL_INVALID_OPERATION} on the draw, which
 * then corrupts the main pass (the whole scene vanishes). So this shader DECLARES all four attributes and keeps
 * the unused ones live (a {@code * 0.0} term the compiler can't cull), matching the main shader's VAO layout.
 */
final class DepthShader {

    private DepthShader() {}

    // Keeps a_normal/a_uv/a_color from being optimised out, so the mesh's VAO layout matches the main shader's.
    private static final String KEEP = "gl_Position.z += 0.0 * (a_normal.x + a_uv.x + a_color.r);";

    private static final String VERT20 = """
            attribute vec3 a_position;
            attribute vec3 a_normal;
            attribute vec2 a_uv;
            attribute vec4 a_color;
            uniform mat4 u_projView;
            uniform mat4 u_world;
            void main() {
                gl_Position = u_projView * u_world * vec4(a_position, 1.0);
                """ + KEEP + """
            }
            """;

    private static String frag20() {
        return "#ifdef GL_ES\nprecision highp float;\n#endif\n"
                + "void main() { gl_FragColor = vec4(1.0); }\n";
    }

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
            void main() {
                mat4 world = mat4(i_w0, i_w1, i_w2, i_w3);
                gl_Position = u_projView * world * vec4(a_position, 1.0);
                """ + KEEP + """
            }
            """;

    private static String frag150() {
        return "#version 150\nout vec4 fragColor;\nvoid main() { fragColor = vec4(1.0); }\n";
    }

    static ShaderProgram create(boolean instanced) {
        ShaderProgram.pedantic = false;
        ShaderProgram s = instanced ? new ShaderProgram(VERT, frag150()) : new ShaderProgram(VERT20, frag20());
        if (!s.isCompiled()) {
            throw new GdxRuntimeException("DepthShader (" + (instanced ? "instanced" : "uniform")
                    + ") failed to compile:\n" + s.getLog());
        }
        return s;
    }
}
