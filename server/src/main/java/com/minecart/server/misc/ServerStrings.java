package com.minecart.server.misc;

/**
 * Stable tag keys used by server-side persistence (e.g. {@code level.dat}). Keep these stable across
 * versions: changing a key invalidates every saved world on disk.
 */
public final class ServerStrings {

    private ServerStrings() {}

    /** {@code level.dat} root key: tick rate as double seconds. */
    public static final String TAG_TICK_RATE = "tick_rate";

    /** {@code level.dat} root key: list of saved worlds (CompoundTags). */
    public static final String TAG_WORLDS = "worlds";

    /** Per-world key: stable {@link com.minecart.foundation.World#getId()} UUID. */
    public static final String TAG_WORLD_ID = "world_id";

    /** Per-world key: list of {@link com.minecart.foundation.Circuit#save} tags. */
    public static final String TAG_CIRCUITS = "circuits";

    /** Default file name for a world's saved state inside its directory. */
    public static final String LEVEL_DAT = "level.dat";
}
