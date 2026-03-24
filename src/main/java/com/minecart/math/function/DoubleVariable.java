package com.minecart.math.function;

import java.util.UUID;

/**
 * Representing a mutable reference that has an unique id
 * @param <T> Type
 */
public class DoubleVariable {
    protected final UUID id;
    double value;

    public DoubleVariable() {
        this.id = UUID.randomUUID();
    }

    public DoubleVariable(UUID id, double value) {
        this.id = id;
        this.value = value;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }

    public UUID getUUID() {
        return id;
    }
}
