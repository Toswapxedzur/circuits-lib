package com.minecart.client.network;

import com.minecart.logic.CircuitComponent;
import com.minecart.serialization.tag.CompoundTag;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Pairs a sync writer and reader for a {@link CircuitComponent} type. Use {@link #combineChain} and {@link #stack}
 * to compose handlers without duplicating registration logic.
 *
 * @param <T> component type
 */
public final class SyncHandler<T extends CircuitComponent> {

    private final BiConsumer<T, CompoundTag> writer;
    private final BiConsumer<T, CompoundTag> reader;

    public SyncHandler(BiConsumer<T, CompoundTag> writer, BiConsumer<T, CompoundTag> reader) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public BiConsumer<T, CompoundTag> writer() {
        return writer;
    }

    public BiConsumer<T, CompoundTag> reader() {
        return reader;
    }

    /**
     * Folds left: {@code steps[0]}, then {@code steps[1]}, … on the same component and tag. Use for writer or reader
     * pipelines (same {@link BiConsumer} shape).
     *
     * @throws IllegalArgumentException if {@code steps} is empty
     */
    @SafeVarargs
    public static <T extends CircuitComponent> BiConsumer<T, CompoundTag> combineChain(
            BiConsumer<T, CompoundTag>... steps) {
        Objects.requireNonNull(steps, "steps");
        if (steps.length == 0) {
            throw new IllegalArgumentException("steps must not be empty");
        }
        BiConsumer<T, CompoundTag> out = Objects.requireNonNull(steps[0], "steps[0]");
        for (int i = 1; i < steps.length; i++) {
            out = out.andThen(Objects.requireNonNull(steps[i], "steps[" + i + "]"));
        }
        return out;
    }

    /**
     * Runs {@code overlay} writers/readers after {@code base} (same tag; overlay can add or overwrite keys).
     */
    public static <T extends CircuitComponent> SyncHandler<T> stack(
            SyncHandler<T> base,
            BiConsumer<T, CompoundTag> moreWriter,
            BiConsumer<T, CompoundTag> moreReader) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(moreWriter, "moreWriter");
        Objects.requireNonNull(moreReader, "moreReader");
        return new SyncHandler<>(
                combineChain(base.writer(), moreWriter),
                combineChain(base.reader(), moreReader));
    }

    /**
     * Runs {@code base} first, then {@code overlay}, for both write and read.
     */
    public static <T extends CircuitComponent> SyncHandler<T> stack(SyncHandler<T> base, SyncHandler<T> overlay) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(overlay, "overlay");
        return new SyncHandler<>(
                combineChain(base.writer(), overlay.writer()),
                combineChain(base.reader(), overlay.reader()));
    }

    /**
     * Builds a handler from two bi-consumers (same as {@code new SyncHandler<>(writer, reader)}).
     */
    public static <T extends CircuitComponent> SyncHandler<T> of(
            BiConsumer<T, CompoundTag> writer, BiConsumer<T, CompoundTag> reader) {
        return new SyncHandler<>(writer, reader);
    }
}
