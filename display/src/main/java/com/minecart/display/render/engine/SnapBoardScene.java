package com.minecart.display.render.engine;

import com.minecart.display.snap.SnapModelBridge;
import com.minecart.snap.SnapPlacement;

/**
 * The Phase-B bridge from the snap-board domain into the instanced engine. Takes a board snapshot (a set of
 * {@link SnapPlacement}s), runs each through the pure {@link SnapModelBridge} to get an engine model id + a
 * world matrix, and registers it with an {@link EngineRenderer} as a {@link EngineRenderer.DynamicEntity}.
 *
 * <p>Placements use the <b>dynamic-entity</b> path (a full per-placement matrix) rather than the translation-only
 * static bake, because {@link SnapModelBridge#world} yaws each part to its heading — a rotation the static bake
 * would silently drop. Placements don't move, so their pose is set once here.
 *
 * <p>This is the seam the real 3D {@code SnapScreen} will sit on: on a board {@code revision()} bump it rebuilds
 * the engine's entity set from a fresh snapshot. (Per-model instancing — one mesh + N transforms for identical
 * parts — is the production optimisation over one-mesh-per-placement; added when boards get large.)
 */
final class SnapBoardScene {

    private SnapBoardScene() {}

    /**
     * Registers every placement as a posed engine entity. Call before {@link EngineRenderer#build()}. Any model
     * id the bridge emits must resolve through {@code loader} (missing ids throw there, surfacing datagen gaps).
     */
    static void populate(EngineRenderer engine, ModelLoader loader, Iterable<SnapPlacement> placements) {
        for (SnapModelBridge.Placed p : SnapModelBridge.parts(placements)) {
            EngineRenderer.DynamicEntity e = new EngineRenderer.DynamicEntity(loader.model(p.modelId()));
            e.pose(p.world());
            engine.addEntity(e);
        }
    }
}
