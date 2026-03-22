package com.minecart.math.function;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

public interface VariableHolder<T> {
    Variable<T> computeIfAbsent(UUID id, T value);
}
