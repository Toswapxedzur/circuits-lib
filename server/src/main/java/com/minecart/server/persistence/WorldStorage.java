package com.minecart.server.persistence;

import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.logic.ServerCircuit;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.ListTag;
import com.minecart.serialization.tag.Tag;
import com.minecart.server.misc.ServerStrings;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Saves and loads a {@link ServerLevel} as a single {@code level.dat} compound tag inside a world directory.
 * Layout:
 * <pre>
 * &lt;worldDir&gt;/
 *   level.dat   # CompoundTag {
 *               #   tick_rate: Double
 *               #   worlds:    ListTag [
 *               #     CompoundTag {
 *               #       world_id: UUID
 *               #       circuits: ListTag [Circuit.save() tags]
 *               #     }, ...
 *               #   ]
 *               # }
 * </pre>
 * {@link #save} writes atomically (write to {@code level.dat.tmp} then rename) so a crash mid-save can never
 * leave a half-written file. {@link #load} is a no-op when {@code level.dat} doesn't exist (fresh world).
 */
public final class WorldStorage {

    private WorldStorage() {}

    /**
     * Serializes {@code level} into {@code worldDir/level.dat} (creating the directory if needed). Atomic on POSIX
     * filesystems; falls back to non-atomic rename on platforms that don't support {@link StandardCopyOption#ATOMIC_MOVE}.
     */
    public static void save(ServerLevel level, Path worldDir) throws IOException {
        Files.createDirectories(worldDir);
        Path target = worldDir.resolve(ServerStrings.LEVEL_DAT);
        Path tmp = worldDir.resolve(ServerStrings.LEVEL_DAT + ".tmp");

        CompoundTag root = new CompoundTag();
        root.putDouble(ServerStrings.TAG_TICK_RATE, level.getTickRate());
        ListTag worlds = new ListTag();
        for (World w : level.getWorlds()) {
            CompoundTag wt = new CompoundTag();
            TagUtil.putUUID(wt, ServerStrings.TAG_WORLD_ID, w.getId());
            ListTag circuits = new ListTag();
            for (Circuit c : w.getCircuits()) {
                CompoundTag ct = new CompoundTag();
                c.save(ct);
                circuits.add(ct);
            }
            wt.put(ServerStrings.TAG_CIRCUITS, circuits);
            worlds.add(wt);
        }
        root.put(ServerStrings.TAG_WORLDS, worlds);

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            Tag.writeBinary(out, root);
        }
        atomicMoveOrFallback(tmp, target);
    }

    /**
     * Reads {@code worldDir/level.dat} into {@code level}. Existing worlds are kept; saved worlds with the same id
     * are reused via {@link ServerLevel#getOrCreateWorld(UUID)}, then their circuits are appended.
     * <p>
     * Returns {@code true} if a save file was found and applied, {@code false} if {@code level.dat} did not exist
     * (caller may seed a fresh world).
     */
    public static boolean load(Path worldDir, ServerLevel level) throws IOException {
        Path source = worldDir.resolve(ServerStrings.LEVEL_DAT);
        if (!Files.exists(source)) {
            return false;
        }
        Tag.BinaryWithContext decoded;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(source)))) {
            decoded = Tag.readBinary(in);
        }
        Tag rootTag = decoded.root();
        if (!(rootTag instanceof CompoundTag root)) {
            throw new IOException("Expected CompoundTag root in " + source + ", got " + rootTag.getClass().getName());
        }
        if (root.get(ServerStrings.TAG_TICK_RATE) != null) {
            level.setTickRate(root.getDouble(ServerStrings.TAG_TICK_RATE));
        }
        Tag worldsTag = root.get(ServerStrings.TAG_WORLDS);
        if (worldsTag instanceof ListTag worlds) {
            for (int i = 0; i < worlds.size(); i++) {
                CompoundTag wt = TagUtil.requireCompoundTag(worlds.get(i), ServerStrings.TAG_WORLDS + "[" + i + "]");
                UUID worldId = TagUtil.getUUID(wt, ServerStrings.TAG_WORLD_ID);
                if (worldId == null) {
                    throw new IOException("Missing " + ServerStrings.TAG_WORLD_ID + " in " + ServerStrings.TAG_WORLDS + "[" + i + "]");
                }
                ServerWorld sw = level.getOrCreateWorld(worldId);
                Tag circuitsTag = wt.get(ServerStrings.TAG_CIRCUITS);
                if (circuitsTag instanceof ListTag circuits) {
                    for (int j = 0; j < circuits.size(); j++) {
                        CompoundTag ct = TagUtil.requireCompoundTag(
                                circuits.get(j),
                                ServerStrings.TAG_CIRCUITS + "[" + j + "]");
                        loadCircuit(sw, ct);
                    }
                }
            }
        }
        return true;
    }

    private static void loadCircuit(ServerWorld world, CompoundTag ct) {
        UUID circuitId = TagUtil.getUUID(ct, com.minecart.misc.CoreStrings.CIRCUIT_ID);
        if (circuitId == null) {
            throw new IllegalArgumentException("Missing " + com.minecart.misc.CoreStrings.CIRCUIT_ID + " in saved circuit");
        }
        ServerCircuit circuit = new ServerCircuit(circuitId);
        world.addCircuit(circuit);
        circuit.load(world, ct);
    }

    /**
     * Convenience: write an empty save (one world with the given id, no circuits) into {@code worldDir}. Used
     * when first creating a world from the UI so subsequent loads see a valid file.
     */
    public static void writeEmpty(Path worldDir, UUID worldId) throws IOException {
        Files.createDirectories(worldDir);
        Path target = worldDir.resolve(ServerStrings.LEVEL_DAT);
        Path tmp = worldDir.resolve(ServerStrings.LEVEL_DAT + ".tmp");

        CompoundTag root = new CompoundTag();
        ListTag worlds = new ListTag();
        CompoundTag wt = new CompoundTag();
        TagUtil.putUUID(wt, ServerStrings.TAG_WORLD_ID, worldId);
        wt.put(ServerStrings.TAG_CIRCUITS, new ListTag());
        worlds.add(wt);
        root.put(ServerStrings.TAG_WORLDS, worlds);

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            Tag.writeBinary(out, root);
        }
        atomicMoveOrFallback(tmp, target);
    }

    private static void atomicMoveOrFallback(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
