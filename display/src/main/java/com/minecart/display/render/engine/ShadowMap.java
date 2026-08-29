package com.minecart.display.render.engine;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

/**
 * A directional <b>shadow map</b>: an orthographic depth render of the scene from the key light's point of view,
 * into a colour FBO (RGBA-packed depth via {@link DepthShader}). The main shader projects each fragment into this
 * light space and compares depth to decide shadow — giving real cast shadows ("traceable", not Minecraft-style
 * blocky light). Aimed at the scene's bounding sphere each frame.
 */
final class ShadowMap implements Disposable {

    private final int size;
    private final FrameBuffer fbo;
    private final Matrix4 view = new Matrix4();
    private final Matrix4 proj = new Matrix4();
    private final Matrix4 viewProj = new Matrix4();
    private final Vector3 eye = new Vector3();
    private final Vector3 up = new Vector3();

    ShadowMap(int size) {
        this.size = size;
        this.fbo = new FrameBuffer(Pixmap.Format.RGBA8888, size, size, true);
        Texture t = fbo.getColorBufferTexture();
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        t.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
    }

    /** Aims the light camera at the scene sphere ({@code centre},{@code radius}) along {@code lightDir} (TO the
     *  light) and binds + clears the FBO for the depth pass. Call {@link #end()} after rendering the scene. */
    void begin(Vector3 centre, float radius, Vector3 lightDir) {
        float dist = radius * 2f;
        eye.set(lightDir).scl(dist).add(centre);
        up.set(Math.abs(lightDir.y) > 0.99f ? Vector3.Z : Vector3.Y);
        view.setToLookAt(eye, centre, up);
        // Near/far hug the scene sphere so the packed-depth precision is spent on the scene, not empty space.
        proj.setToOrtho(-radius, radius, -radius, radius, dist - radius * 1.1f, dist + radius * 1.1f);
        viewProj.set(proj).mul(view);

        fbo.begin();
        Gdx.gl.glViewport(0, 0, size, size);
        Gdx.gl.glClearColor(1f, 1f, 1f, 1f); // cleared to far (max) depth
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
        return fbo.getColorBufferTexture();
    }

    @Override
    public void dispose() {
        fbo.dispose();
    }
}
