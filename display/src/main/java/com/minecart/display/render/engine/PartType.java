package com.minecart.display.render.engine;

import com.badlogic.gdx.utils.Disposable;

/**
 * A distinct kind of renderable sub-part (e.g. a snap stud, a capacitor body, a slider knob). All instances
 * of a part-type share its one {@link PartMesh}, so the renderer draws every instance of the type in a single
 * GPU-instanced call. Components are composed FROM part-types (see {@link ComponentModel}); shared part-types
 * (the stud) instance across every component that uses them.
 */
final class PartType implements Disposable {

    final String id;
    final PartMesh mesh;

    PartType(String id, PartMesh mesh) {
        this.id = id;
        this.mesh = mesh;
    }

    @Override
    public void dispose() {
        mesh.dispose();
    }
}
