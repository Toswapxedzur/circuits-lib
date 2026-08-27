package com.minecart.display.render.engine;

import java.util.List;

/**
 * A distinct kind of movable sub-part (e.g. a slider knob), as pure data: an id plus its boxes in local space.
 * The renderer builds ONE {@link PartMesh} per part-type (against the shared atlas) and draws every instance of
 * the type in a single GPU-instanced call. Keeping this GL-free lets all boxes be known — and the atlas built —
 * before any mesh, which the baked-UV path requires.
 */
record PartType(String id, List<PartMesh.Box> boxes) {}
