package com.minecart.display.render;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Small client-local persistence helper for editor camera state. Kept separate from level.dat so
 * server/world data remains protocol-owned while UI preferences stay local to this machine.
 */
public final class WorldViewStorage {

    private static final String FILE_NAME = "view.properties";
    private static final String SELECTED_WORLD = "selectedWorld";

    private WorldViewStorage() {}

    public static Snapshot load(Path saveDir) {
        if (saveDir == null) {
            return Snapshot.empty();
        }
        Path file = saveDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return Snapshot.empty();
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException ignored) {
            return Snapshot.empty();
        }

        UUID selected = parseUuid(props.getProperty(SELECTED_WORLD));
        Map<UUID, WorldViewState> views = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith("world.") || !name.endsWith(".zoom")) {
                continue;
            }
            String idText = name.substring("world.".length(), name.length() - ".zoom".length());
            UUID id = parseUuid(idText);
            if (id == null) {
                continue;
            }
            Float x = parseFloat(props.getProperty("world." + id + ".x"));
            Float y = parseFloat(props.getProperty("world." + id + ".y"));
            Float zoom = parseFloat(props.getProperty("world." + id + ".zoom"));
            if (x != null && y != null && zoom != null) {
                views.put(id, new WorldViewState(x, y, zoom));
            }
        }
        return new Snapshot(selected, views);
    }

    public static void save(Path saveDir, UUID selectedWorldId, Map<UUID, WorldViewState> views)
            throws IOException {
        if (saveDir == null) {
            return;
        }
        Files.createDirectories(saveDir);
        Properties props = new Properties();
        if (selectedWorldId != null) {
            props.setProperty(SELECTED_WORLD, selectedWorldId.toString());
        }
        if (views != null) {
            for (Map.Entry<UUID, WorldViewState> entry : views.entrySet()) {
                UUID id = entry.getKey();
                WorldViewState view = entry.getValue();
                if (id == null || view == null) {
                    continue;
                }
                props.setProperty("world." + id + ".x", Float.toString(view.cameraX()));
                props.setProperty("world." + id + ".y", Float.toString(view.cameraY()));
                props.setProperty("world." + id + ".zoom", Float.toString(view.zoom()));
            }
        }
        try (OutputStream out = Files.newOutputStream(saveDir.resolve(FILE_NAME))) {
            props.store(out, "CircuitsLib editor view state");
        }
    }

    private static UUID parseUuid(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Float parseFloat(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            float value = Float.parseFloat(text.trim());
            return Float.isFinite(value) ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public record Snapshot(UUID selectedWorldId, Map<UUID, WorldViewState> views) {
        static Snapshot empty() {
            return new Snapshot(null, Map.of());
        }
    }
}
