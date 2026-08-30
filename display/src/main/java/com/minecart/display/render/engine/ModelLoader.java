package com.minecart.display.render.engine;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The runtime model loader — reads the committed {@code models/parts/<id>.json} (written by {@link GenModels})
 * into a {@link ComponentModel}, resolving a movable's borrowed {@link PartType} and any {@code parent}
 * inheritance on the way. Each element becomes a paint-free {@link PartMesh.Box} carrying its per-face sprite
 * names, so the runtime never touches {@link PaletteDither} — it just names atlas sprites. Because everything
 * comes from resource files, the parts are fully moddable (drop-in / override the JSON or the PNGs).
 */
final class ModelLoader {

    private final Gson gson = new Gson();
    private final Map<String, ModelJson> jsonCache = new HashMap<>();
    private final Map<String, PartType> partCache = new HashMap<>();

    /** Loads a component model by id (e.g. {@code "switch_lime"}). */
    ComponentModel model(String id) {
        ModelJson j = read(id);
        ComponentModel.Builder b = ComponentModel.of(j.id);
        List<PartMesh.Box> boxes = new ArrayList<>();
        collectBoxes(j, boxes);
        for (PartMesh.Box box : boxes) b.box(box);
        List<PartMesh.Quad> quads = new ArrayList<>();
        collectQuads(j, quads);
        for (PartMesh.Quad q : quads) b.quad(q);
        if (j.movables != null) {
            for (ModelJson.Movable mv : j.movables) {
                b.movable(partType(mv.part), mv.at[0], mv.at[1], mv.at[2],
                        new BindingSpec(mv.bindingType, mv.channel, mv.axis, mv.pivot, mv.degPerUnit));
            }
        }
        if (j.collision != null) {
            ComponentModel.Collision col = toCollision(j.collision);
            b.collision(col);
            // Default connectors (until datagen emits them): two terminals near the part's two ends along its
            // long axis (X for the standard parts), at the base plane (y=0), studs pointing UP (+Y). terminal 0/1.
            float ex = Math.max(1f, col.hx() - 1.5f);         // just inside each end
            com.badlogic.gdx.math.Vector3 up = new com.badlogic.gdx.math.Vector3(0f, 1f, 0f);
            b.connector(new ComponentModel.Connector(new com.badlogic.gdx.math.Vector3(col.cx() - ex, 0f, col.cz()), up, 0, true));
            b.connector(new ComponentModel.Connector(new com.badlogic.gdx.math.Vector3(col.cx() + ex, 0f, col.cz()), up, 1, true));
        }
        return b.build();
    }

    /** Converts the JSON AABB (from/to corners) into the runtime collision box (centre + half-extents). */
    private static ComponentModel.Collision toCollision(ModelJson.Collision c) {
        float cx = (c.from[0] + c.to[0]) / 2f, cy = (c.from[1] + c.to[1]) / 2f, cz = (c.from[2] + c.to[2]) / 2f;
        float hx = (c.to[0] - c.from[0]) / 2f, hy = (c.to[1] - c.from[1]) / 2f, hz = (c.to[2] - c.from[2]) / 2f;
        ComponentModel.FaceMaterial[] faces = null;
        if (c.faces != null) {
            faces = new ComponentModel.FaceMaterial[c.faces.length];
            for (int i = 0; i < c.faces.length; i++) {
                ModelJson.Face f = c.faces[i];
                if (f != null) faces[i] = new ComponentModel.FaceMaterial(f.friction, f.restitution, f.solid);
            }
        }
        return new ComponentModel.Collision(cx, cy, cz, hx, hy, hz, c.friction, c.restitution, faces);
    }

    /** Loads (and caches) a movable part-type by id (a model JSON with only elements). */
    private PartType partType(String id) {
        PartType cached = partCache.get(id);
        if (cached != null) return cached;
        List<PartMesh.Box> boxes = new ArrayList<>();
        collectBoxes(read(id), boxes);
        PartType t = new PartType(id, boxes);
        partCache.put(id, t);
        return t;
    }

    /** Appends this model's elements as boxes, prepending any {@code parent}'s elements first (borrow). */
    private void collectBoxes(ModelJson j, List<PartMesh.Box> out) {
        if (j.parent != null) collectBoxes(read(j.parent), out);
        if (j.elements == null) return;
        for (ModelJson.Element e : j.elements) {
            float cx = (e.from[0] + e.to[0]) / 2f, cy = (e.from[1] + e.to[1]) / 2f, cz = (e.from[2] + e.to[2]) / 2f;
            float sx = e.to[0] - e.from[0], sy = e.to[1] - e.from[1], sz = e.to[2] - e.from[2];
            String[] faces = new String[6];
            if (e.faces != null) e.faces.forEach((k, v) -> faces[ModelJson.faceId(k)] = v);
            out.add(PartMesh.Box.loaded(cx, cy, cz, sx, sy, sz, faces, e.tint, e.translucent));
        }
    }

    /** Appends this model's oriented quads (prepending any parent's first). */
    private void collectQuads(ModelJson j, List<PartMesh.Quad> out) {
        if (j.parent != null) collectQuads(read(j.parent), out);
        if (j.quads == null) return;
        for (ModelJson.Quad q : j.quads) {
            float[][] c = q.corners;
            out.add(PartMesh.Quad.loaded(
                    new Vector3(c[0][0], c[0][1], c[0][2]), new Vector3(c[1][0], c[1][1], c[1][2]),
                    new Vector3(c[2][0], c[2][1], c[2][2]), new Vector3(c[3][0], c[3][1], c[3][2]),
                    q.w, q.h, q.sprite));
        }
    }

    private ModelJson read(String id) {
        ModelJson cached = jsonCache.get(id);
        if (cached != null) return cached;
        FileHandle f = Gdx.files.internal("models/parts/" + id + ".json");
        if (!f.exists()) {
            throw new GdxRuntimeException("Missing model JSON: " + f.path()
                    + " — run ./gradlew :display:genmodels first.");
        }
        ModelJson j = gson.fromJson(f.readString("UTF-8"), ModelJson.class);
        jsonCache.put(id, j);
        return j;
    }
}
