package com.minecart.display.world;

import com.badlogic.gdx.files.FileHandle;

/**
 * One singleplayer world stored on disk. The world's identity is its directory name.
 * Future per-world state (saved circuits, settings) will live inside {@link #dir()}.
 */
public final class WorldEntry {

    private final String name;
    private final FileHandle dir;
    private final long lastModified;

    public WorldEntry(String name, FileHandle dir, long lastModified) {
        this.name = name;
        this.dir = dir;
        this.lastModified = lastModified;
    }

    public String name() { return name; }
    public FileHandle dir() { return dir; }
    public long lastModified() { return lastModified; }
}
