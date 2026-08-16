package com.minecart.display.world;

import com.badlogic.gdx.files.FileHandle;
import com.minecart.foundation.GameMode;

/**
 * One singleplayer world stored on disk. The world's identity is its directory name.
 * Future per-world state (saved circuits, settings) will live inside {@link #dir()}.
 */
public final class WorldEntry {

    private final String name;
    private final FileHandle dir;
    private final long lastModified;
    private final GameMode mode;

    public WorldEntry(String name, FileHandle dir, long lastModified, GameMode mode) {
        this.name = name;
        this.dir = dir;
        this.lastModified = lastModified;
        this.mode = mode == null ? GameMode.FLAT_2D : mode;
    }

    public String name() { return name; }
    public FileHandle dir() { return dir; }
    public long lastModified() { return lastModified; }

    /** The building paradigm this save was created with (peeked from its {@code level.dat}). */
    public GameMode mode() { return mode; }
}
