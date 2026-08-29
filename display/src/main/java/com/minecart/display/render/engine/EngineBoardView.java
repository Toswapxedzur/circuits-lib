package com.minecart.display.render.engine;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;
import com.minecart.display.snap.SnapModelBridge;
import com.minecart.snap.SnapPlacement;

import java.util.ArrayList;
import java.util.List;

/**
 * The public seam that lets the app's {@code SnapScreen} render a {@link com.minecart.snap.SnapBoard} through the
 * instanced engine — showing the real detailed part models (capacitor/resistor/LED/wire/battery/…) instead of the
 * legacy {@code SnapRenderer}'s plain boxes. It hides the package-private engine ({@link EngineRenderer},
 * {@link ModelLoader}, {@link SnapBoardScene}) behind a small API.
 *
 * <p>The engine auto-selects its GL20 uniform-draw path inside the app's GL2.0 context (see {@link
 * InstancedShader}); no core context is required, so it coexists with the GLSL-120 scene2d menus.
 *
 * <p>Usage: {@link #setBoard} on first show and whenever the board's {@code revision()} advances, then
 * {@link #render} each frame with the screen's camera.
 */
public final class EngineBoardView implements Disposable {

    private final EngineRenderer engine = new EngineRenderer();
    private final ModelLoader loader = new ModelLoader();
    private boolean built;
    private boolean hasBase;

    public EngineBoardView() {
    }

    /**
     * Adds the <b>base board</b> the parts sit on — a {@code cols}×{@code rows} grid tiled from the committed
     * (datagen) board-cell + stud sprites (see {@link SnapBaseBoard}), top surface at {@code topY}. Call ONCE
     * before the first {@link #setBoard}; it becomes static geometry (survives part rebuilds) so the board stays
     * visible even when empty. Nothing is generated at runtime — only committed sprites are tiled.
     */
    public void setBaseBoard(int cols, int rows, float topY) {
        engine.addStatic(SnapBaseBoard.build(cols, rows, topY));
        hasBase = true;
    }

    /** Rebuilds the drawn parts from a board snapshot (drop → repopulate → bake). Call on show + on revision bump.
     *  The base board (if set) is static, so it persists across rebuilds and keeps the board visible when empty. */
    public void setBoard(Iterable<SnapPlacement> placements) {
        boolean anyParts = placements.iterator().hasNext();
        if (!anyParts && !hasBase) {
            // Nothing at all to bake (an empty atlas would be degenerate). Stop drawing until parts return.
            engine.clearEntities();
            built = false;
            return;
        }
        SnapBoardScene.rebuild(engine, loader, placements); // clears entities, repopulates, rebakes (base slab kept)
        built = true;
    }

    /** Draws the current board with {@code cam}. No-op until the first {@link #setBoard}. */
    public void render(Camera cam) {
        if (built) {
            engine.render(cam);
        }
    }

    /** A placed part's static collider: an axis-aligned box (half-extents from the model's datagen collision box)
     *  at a world transform (the placement's transform, shifted to the box centre). {@code world} may be yawed —
     *  the box is axis-aligned in the part's local frame, oriented in world. */
    public record PartCollider(float hx, float hy, float hz, Matrix4 world) {}

    /** The static colliders for a board snapshot — one axis-aligned box per placement that has a collision box.
     *  The physics world adds these as static bodies so entities rest on/against the board parts. */
    public List<PartCollider> colliders(Iterable<SnapPlacement> placements) {
        List<PartCollider> out = new ArrayList<>();
        for (SnapPlacement p : placements) {
            ComponentModel m = loader.model(SnapModelBridge.modelId(p));
            ComponentModel.Collision c = m.collision;
            if (c == null) {
                continue;
            }
            Matrix4 world = SnapModelBridge.world(p).translate(c.cx(), c.cy(), c.cz()); // to the AABB centre
            out.add(new PartCollider(c.hx(), c.hy(), c.hz(), world));
        }
        return out;
    }

    @Override
    public void dispose() {
        engine.dispose();
    }
}
