package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.GdxRuntimeException;

/**
 * The <b>shadow-map depth</b> shader: renders the scene from the key light's orthographic point of view, writing
 * each fragment's light-space depth (RGBA-<b>packed</b> for precision + GL2.0/GL3.0 portability) into a colour
 * FBO. {@link EngineRenderer}'s main shader later samples this to decide what's in shadow. Two variants matching
 * {@link InstancedShader}: instanced (GL3.2, per-instance {@code i_w0..i_w3}) and uniform (GL2.0, {@code u_world}).
 * Only positions matter here — no normals/UVs/lighting.
 */
final class DepthShader {

    private DepthShader() {}

    /** Packs a [0,1] depth into RGBA8 (≈32-bit); {@code unpackDepth} in the main shader reverses it. */
    static final String PACK = """
            vec4 packDepth(float d) {
                vec4 enc = vec4(1.0, 255.0, 65025.0, 16581375.0) * d;
                enc = fract(enc);
                enc -= enc.yzww * vec4(1.0/255.0, 1.0/255.0, 1.0/255.0, 0.0);
                return enc;
            }
            """;

    private static final String VERT20 = """
            attribute vec3 a_position;
            uniform mat4 u_projView;
            uniform mat4 u_world;
            void main() {
                gl_Position = u_projView * u_world * vec4(a_position, 1.0);
            }
            """;

    private static String frag20() {
        return "#ifdef GL_ES\nprecision highp float;\n#endif\n"
                + PACK
                + "void main() { gl_FragColor = packDepth(gl_FragCoord.z); }\n";
    }

    private static final String VERT = """
            #version 150
            in vec3 a_position;
            in vec4 i_w0;
            in vec4 i_w1;
            in vec4 i_w2;
            in vec4 i_w3;
            uniform mat4 u_projView;
            void main() {
                mat4 world = mat4(i_w0, i_w1, i_w2, i_w3);
                gl_Position = u_projView * world * vec4(a_position, 1.0);
            }
            """;

    private static String frag150() {
        return "#version 150\n" + PACK + "out vec4 fragColor;\n"
                + "void main() { fragColor = packDepth(gl_FragCoord.z); }\n";
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
