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

    // Placement ghost — the real component model, drawn translucent at a pose that SMOOTHLY EASES toward its true
    // target (never snapping). green-ish = valid, red-ish = blocked. Position is lerped and rotation is SLERPed
    // (via quaternion) separately — a naive 16-element Matrix4.lerp would skew/shrink the model mid-rotation.
    private static final float GHOST_EASE = 12f;   // per-second easing rate toward the true pose
    private static final float GHOST_ALPHA = 0.5f; // translucency of the preview
    private final com.badlogic.gdx.math.Vector3 tgtPos = new com.badlogic.gdx.math.Vector3();
    private final com.badlogic.gdx.math.Vector3 dispPos = new com.badlogic.gdx.math.Vector3();
    private final com.badlogic.gdx.math.Quaternion tgtRot = new com.badlogic.gdx.math.Quaternion();
    private final com.badlogic.gdx.math.Quaternion dispRot = new com.badlogic.gdx.math.Quaternion();
    private final Matrix4 ghostDisplayed = new Matrix4();
    private String ghostModelId;
    private boolean ghostPresent;
    private boolean ghostValid;

    public EngineBoardView() {
        // Pre-register every model the ghost can preview so their sprites are in the atlas + a mesh is baked.
        for (String id : SnapModelBridge.allModelIds()) {
            engine.addGhostModel(id, loader.model(id));
        }
    }

    /** Sets the placement ghost for this frame: the {@code modelId} to preview at world {@code truePose}, whether
     *  the target is {@code valid}, ORE hidden ({@code present=false}). The DISPLAYED pose eases toward
     *  {@code truePose} over {@code dt} (position + rotation), so the preview glides rather than jumps.
     *  {@code present=false} hides it. */
    public void setGhost(boolean present, String modelId, Matrix4 truePose, boolean valid, float dt) {
        if (!present || modelId == null) {
            ghostPresent = false;
            return;
        }
        boolean modelChanged = !modelId.equals(ghostModelId);
        ghostModelId = modelId;
        ghostValid = valid;
        truePose.getTranslation(tgtPos);
        truePose.getRotation(tgtRot, true);
        if (!ghostPresent || modelChanged) {
            dispPos.set(tgtPos); // snap on first appear / tool switch, then ease from there
            dispRot.set(tgtRot);
        } else {
            float k = Math.min(1f, dt * GHOST_EASE);
            dispPos.lerp(tgtPos, k);       // smooth position
            dispRot.slerp(tgtRot, k);      // smooth rotation (proper quaternion slerp, not a matrix component-lerp)
        }
        ghostDisplayed.set(dispPos, dispRot);
        ghostPresent = true;
    }

    /** Sets the skylight direction (TO the light; default 45°/45°). Call before the first {@link #setBoard} so the
     *  baked octant variant is chosen at atlas build. See {@link EngineRenderer#setLightDir}. */
    public void setLightDir(float x, float y, float z) {
        engine.setLightDir(x, y, z);
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

    /** Draws the current board with {@code cam}, then the placement ghost (if any) on top. No-op until the first
     *  {@link #setBoard}. */
    public void render(Camera cam) {
        if (!built) {
            return;
        }
        engine.render(cam);
        if (ghostPresent) {
            // Valid = the real component, gently dimmed; blocked = washed red. Both at GHOST_ALPHA transparency.
            if (ghostValid) {
                engine.drawGhost(cam, ghostModelId, ghostDisplayed, 1f, 1f, 1f, GHOST_ALPHA);
            } else {
                engine.drawGhost(cam, ghostModelId, ghostDisplayed, 1f, 0.45f, 0.4f, GHOST_ALPHA);
            }
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
