package com.minecart.math.function;

import java.util.UUID;

public interface VariableHolder<T> {
    ContinuousVariable<T> computeIfAbsent(UUID id, T value);
}
