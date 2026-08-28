package com.minecart.display.render.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The <b>model generator</b> — the datagen half that produces the model JSONs (its sibling {@link
 * SeedPartTextures} produces the PNG textures). It serialises every {@link Parts#registry() component model}
 * and {@link Parts#partTypes() part-type} to {@code src/main/resources/models/parts/<id>.json}, in dependency
 * order via {@link Datagen}: a model borrows the textures its faces name and the part-types its movables use,
 * so those are resolved first, and a texture a model needs must ALREADY exist as a PNG (run {@code
 * :display:seedtextures} first) — a missing or cyclic dependency aborts the run.
 *
 * <p>Official code, run on demand, NOT part of the build: {@code ./gradlew :display:genmodels}, then commit the
 * JSON. The runtime only ever LOADS these files ({@link ModelLoader}), and they are fully moddable.
 */
public final class GenModels {

    private static final String MODEL_DIR = "src/main/resources/models/parts";     // relative to the :display dir
    private static final String TEX_DIR = "src/main/resources/textures/parts";

    public static void main(String[] args) throws IOException {
        Parts parts = new Parts();

        // Reverse map PartType → its id, so a movable can name the part-type it borrows.
        Map<PartType, String> typeIds = new IdentityHashMap<>();
        parts.partTypes().forEach((id, t) -> typeIds.put(t, id));

        // Build every model JSON (part-types first — they have no movables), keyed by "model:<id>".
        Map<String, ModelJson> models = new LinkedHashMap<>();
        parts.partTypes().forEach((id, t) ->
                models.put("model:" + id, ModelJson.of(id, t.boxes(), List.of(), typeIds)));
        parts.registry().forEach((id, m) ->
                models.put("model:" + id, ModelJson.of(id, m.staticBoxes, m.movableParts, typeIds)));

        // The dependency graph: texture leaves + part/model nodes that borrow them.
        Map<String, List<String>> deps = new LinkedHashMap<>();
        for (ModelJson j : models.values()) {
            for (String tex : j.textureDeps()) deps.putIfAbsent("tex:" + tex, List.of());
        }
        for (Map.Entry<String, ModelJson> e : models.entrySet()) {
            List<String> d = new ArrayList<>();
            for (String tex : e.getValue().textureDeps()) d.add("tex:" + tex);
            for (String part : e.getValue().partDeps()) d.add("model:" + part);
            deps.put(e.getKey(), d);
        }

        List<String> ordered = Datagen.order(deps); // topological; throws on cross/cyclic dependency

        Path modelDir = Path.of(MODEL_DIR);
        Path texDir = Path.of(TEX_DIR);
        Files.createDirectories(modelDir);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

        int wroteModels = 0, checkedTex = 0;
        for (String node : ordered) {
            if (node.startsWith("tex:")) {
                // Dependency must be generated first: the PNG has to exist already.
                String name = node.substring(4);
                if (!Files.exists(texDir.resolve(name + ".png"))) {
                    throw new IllegalStateException("Texture dependency '" + name
                            + ".png' is missing — run ./gradlew :display:seedtextures first.");
                }
                checkedTex++;
            } else { // "model:<id>"
                ModelJson j = models.get(node);
                Files.writeString(modelDir.resolve(j.id + ".json"), gson.toJson(j), StandardCharsets.UTF_8);
                wroteModels++;
            }
        }
        System.out.println("[genmodels] wrote " + wroteModels + " model JSONs to " + modelDir.toAbsolutePath()
                + " (" + checkedTex + " texture deps verified present)");
    }

    private GenModels() {}
}
