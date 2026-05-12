package com.minecart.protocol.sync;

/**
 * Registers all {@link SyncRegistry} client sync handlers in one place. Runs once when this class is first loaded
 * (triggered lazily on first {@link SyncRegistry#writeSyncData} / {@link SyncRegistry#readSyncData}), or call
 * {@link #registerAll()} from client bootstrap if registrations must be rebuilt (e.g. after mods).
 */
public final class SyncHandlers {

    private SyncHandlers() {}

    static {
    }
}
