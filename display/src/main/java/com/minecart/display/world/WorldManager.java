package com.minecart.display.world;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Manages singleplayer worlds as folders under {@code data/worlds/} (relative to the working dir,
 * via {@link com.badlogic.gdx.Files#local}). Each world's identity is its directory name.
 *
 * <p>This class is intentionally thin: it owns the on-disk layout and exposes list/create/delete.
 * Loading the actual circuit data inside a world is the {@link com.minecart.display.screen.GameScreen}'s
 * job once that wiring exists.
 */
public final class WorldManager {

    private static final String ROOT = "data/worlds";
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9 _\\-]{1,48}");

    public WorldManager() {
        FileHandle root = Gdx.files.local(ROOT);
        if (!root.exists()) {
            root.mkdirs();
        }
    }

    /** All worlds, most-recently-modified first. */
    public List<WorldEntry> list() {
        FileHandle root = Gdx.files.local(ROOT);
        List<WorldEntry> out = new ArrayList<>();
        if (!root.exists()) {
            return out;
        }
        for (FileHandle child : root.list()) {
            if (!child.isDirectory()) continue;
            out.add(new WorldEntry(child.name(), child, child.lastModified()));
        }
        out.sort(Comparator.comparingLong(WorldEntry::lastModified).reversed());
        return out;
    }

    /** @return whether {@code name} is allowed (and would not collide with an existing world). */
    public boolean isNameValid(String name) {
        if (name == null) return false;
        String trimmed = name.trim();
        if (!VALID_NAME.matcher(trimmed).matches()) return false;
        FileHandle dir = Gdx.files.local(ROOT + "/" + trimmed);
        return !dir.exists();
    }

    /**
     * Creates an empty world directory. Returns {@code null} if the name is invalid or already exists.
     */
    public WorldEntry create(String name) {
        if (!isNameValid(name)) return null;
        String trimmed = name.trim();
        FileHandle dir = Gdx.files.local(ROOT + "/" + trimmed);
        dir.mkdirs();
        return new WorldEntry(trimmed, dir, dir.lastModified());
    }

    /** Recursively deletes the world's directory. Safe no-op if it no longer exists. */
    public boolean delete(WorldEntry world) {
        if (world == null) return false;
        FileHandle dir = world.dir();
        if (!dir.exists()) return true;
        return dir.deleteDirectory();
    }
}
