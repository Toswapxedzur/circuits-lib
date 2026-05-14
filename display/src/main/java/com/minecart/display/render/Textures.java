package com.minecart.display.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Disposable;
import com.minecart.registry.CircuitElementType;

import java.util.HashMap;
import java.util.Map;

/**
 * Lazy cache of {@link Texture}s keyed by {@link CircuitElementType}, loaded from {@code texture/<id>.png}
 * on the classpath. Falls back to a 1×1 magenta placeholder so missing assets don't crash rendering.
 *
 * <p>Owned by the {@link com.minecart.display.screen.GameScreen}; {@link #dispose()} releases all loaded GPU
 * textures.
 */
public final class Textures implements Disposable {

    private final Map<String, Texture> byTypeId = new HashMap<>();
    private Texture missing;
    private Texture white;

    /**
     * @return the texture for {@code type}, loading it from the classpath if needed. Always non-null
     *         (returns the placeholder on missing files).
     */
    public Texture get(CircuitElementType<?> type) {
        if (type == null) {
            return missingTexture();
        }
        return getById(type.getTypeId());
    }

    public Texture getById(String typeId) {
        if (typeId == null || typeId.isEmpty()) {
            return missingTexture();
        }
        Texture cached = byTypeId.get(typeId);
        if (cached != null) {
            return cached;
        }
        Texture loaded = load(typeId);
        if (loaded == null) {
            loaded = missingTexture();
        }
        byTypeId.put(typeId, loaded);
        return loaded;
    }

    private Texture load(String typeId) {
        FileHandle file = Gdx.files.internal("texture/" + typeId + ".png");
        if (!file.exists()) {
            // Try a few common aliases (e.g. "bj_transistor" → "bjt.png") so existing assets work.
            String alias = ALIASES.get(typeId);
            if (alias != null) {
                file = Gdx.files.internal("texture/" + alias + ".png");
            }
        }
        if (file.exists()) {
            try {
                return new Texture(file);
            } catch (Throwable t) {
                Gdx.app.log("Textures", "Failed to load " + file.path() + ": " + t.getMessage());
            }
        }
        return null;
    }

    /**
     * Shared 1×1 opaque-white texture, lazily created. Used by {@link EdgeActor} (and anyone else who
     * needs to draw thin coloured rectangles through the sprite batch) to bridge "extra" segment length
     * without the texture-stretching artefacts you get from re-using the edge sprite itself.
     */
    public Texture white() {
        if (white == null) {
            Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pm.setColor(1f, 1f, 1f, 1f);
            pm.fill();
            white = new Texture(pm);
            pm.dispose();
        }
        return white;
    }

    private Texture missingTexture() {
        if (missing == null) {
            Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pm.setColor(1f, 0f, 1f, 1f);
            pm.fill();
            missing = new Texture(pm);
            pm.dispose();
        }
        return missing;
    }

    @Override
    public void dispose() {
        for (Texture t : byTypeId.values()) {
            if (t != null && t != missing) {
                t.dispose();
            }
        }
        byTypeId.clear();
        if (missing != null) {
            missing.dispose();
            missing = null;
        }
        if (white != null) {
            white.dispose();
            white = null;
        }
    }

    private static final Map<String, String> ALIASES = Map.of(
            "bj_transistor", "bjt",
            "connection", "junction"
    );
}
