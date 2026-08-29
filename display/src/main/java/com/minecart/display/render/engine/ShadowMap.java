package com.minecart.display.render.engine;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

/**
 * A directional <b>shadow map</b> using a hardware <b>depth texture</b> (24-bit) — the depth pass writes real GPU
 * depth (no colour-packing), and the main shader samples {@code .r} and compares. The light's orthographic frustum
 * is fit <b>tightly</b> to the scene's world AABB (transformed into light space each frame), so the limited depth
 * range is spent on the actual geometry — the key to avoiding self-shadow acne.
 */
final class ShadowMap implements Disposable {

    private final int size;
    private final FrameBuffer fbo;
    private final Texture depth;
    private final Matrix4 view = new Matrix4();
    private final Matrix4 proj = new Matrix4();
    private final Matrix4 viewProj = new Matrix4();
    private final Vector3 eye = new Vector3();
    private final Vector3 up = new Vector3();
    private final Vector3 corner = new Vector3();

    ShadowMap(int size) {
        this.size = size;
        GLFrameBuffer.FrameBufferBuilder b = new GLFrameBuffer.FrameBufferBuilder(size, size);
        // A colour attachment is required for FBO completeness on this driver (depth-texture-only is incomplete);
        // the depth-pass shader writes a throwaway colour, and we sample the DEPTH texture for shadows.
        b.addBasicColorTextureAttachment(com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
        b.addDepthTextureAttachment(GL30.GL_DEPTH_COMPONENT24, GL30.GL_UNSIGNED_INT);
        this.fbo = b.build();
        // Attachments are returned in add order: [0] = colour, [1] = depth texture.
        this.depth = fbo.getTextureAttachments().get(1);
        depth.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        depth.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        // Sample the raw depth value (compare mode OFF) — we compare in the shader.
        depth.bind();
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_COMPARE_MODE, GL20.GL_NONE);
    }

    /** Aims the light at the world AABB ({@code centre} ± {@code half}) along {@code lightDir} (TO the light),
     *  fitting the ortho tightly to it, and binds + clears the depth FBO for the depth pass. */
    void begin(Vector3 centre, Vector3 half, Vector3 lightDir) {
        float back = half.len() * 2f + 10f;
        eye.set(lightDir).scl(back).add(centre);
        up.set(Math.abs(lightDir.y) > 0.99f ? Vector3.Z : Vector3.Y);
        view.setToLookAt(eye, centre, up);

        // Fit the ortho to the AABB's 8 corners in light-view space (view looks down −z).
        float minx = Float.MAX_VALUE, miny = minx, minz = minx, maxx = -minx, maxy = -minx, maxz = -minx;
        for (int i = 0; i < 8; i++) {
            corner.set(centre.x + ((i & 1) == 0 ? -half.x : half.x),
                    centre.y + ((i & 2) == 0 ? -half.y : half.y),
                    centre.z + ((i & 4) == 0 ? -half.z : half.z)).mul(view);
            minx = Math.min(minx, corner.x); maxx = Math.max(maxx, corner.x);
            miny = Math.min(miny, corner.y); maxy = Math.max(maxy, corner.y);
            minz = Math.min(minz, corner.z); maxz = Math.max(maxz, corner.z);
        }
        proj.setToOrtho(minx, maxx, miny, maxy, -maxz - 1f, -minz + 1f); // near = −maxz (nearest), far = −minz
        viewProj.set(proj).mul(view);

        fbo.begin();
        Gdx.gl.glViewport(0, 0, size, size);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
    }

    void end() {
        fbo.end();
    }

    /** The light's view-projection — main shader projects world positions with this to sample the map. */
    Matrix4 viewProj() {
        return viewProj;
    }

    Texture texture() {
        return depth;
    }

    @Override
    public void dispose() {
        fbo.dispose();
    }
}
