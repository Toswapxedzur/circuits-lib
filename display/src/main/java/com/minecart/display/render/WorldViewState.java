package com.minecart.display.render;

/**
 * Per-world snapshot of the camera state — its pan centre and {@code camera.zoom}. Stored in
 * {@link com.minecart.display.screen.GameScreen} keyed by world id so switching worlds restores both the
 * magnification and the scroll position independently for each world.
 *
 * <p>{@code zoom} here is the raw {@link com.badlogic.gdx.graphics.OrthographicCamera#zoom} value, not a
 * percent — translate via {@link WorldStage#getMagnificationPercent}/{@link WorldStage#setMagnificationPercent}
 * when surfacing it to the user.
 */
public record WorldViewState(float cameraX, float cameraY, float zoom) {

    /** Default view: centred on origin at 100% magnification (camera.zoom = 1). */
    public static WorldViewState defaultView() {
        return new WorldViewState(0f, 0f, 1f);
    }

    public WorldViewState withZoom(float newZoom) {
        return new WorldViewState(cameraX, cameraY, newZoom);
    }
}
