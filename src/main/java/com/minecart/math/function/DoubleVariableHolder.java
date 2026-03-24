package com.minecart.math.function;

import java.util.UUID;

public interface DoubleVariableHolder {
    DoubleVariable computeIfAbsent(UUID id, double value);
}
