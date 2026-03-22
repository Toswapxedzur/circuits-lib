package com.minecart.math.function;

import java.util.UUID;

/**
 * Representing a mutable reference that has an unique id
 * @param <T> Type
 */
public class Variable<T> {
    protected final UUID id;
    T value;

    public Variable() {
        this.id = UUID.randomUUID();
    }

    public T getValue() {
        return value;
    }

    public void setValue(T value) {
        this.value = value;
    }

    public UUID getUUID() {
        return id;
    }

    public static class DoubleVar extends Variable<Double> {
        private double lower;
        private double upper;

        /**
         * Creates a bounded double variable.
         */
        public DoubleVar(double lower, double upper) {
            this.value = 0.0;
            this.lower = lower;
            this.upper = upper;
        }

        /**
         * Creates an unbounded double variable.
         */
        public DoubleVar() {
            this(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        }

        public double getLower() {
            return lower;
        }

        public void setLower(double lower) {
            this.lower = lower;
        }

        public double getUpper() {
            return upper;
        }

        public void setUpper(double upper) {
            this.upper = upper;
        }

        /**
         * Constrains a given value to this variable's strict physical or mathematical bounds.
         * Crucial for preventing solver divergence.
         */
        public double clamp(double value) {
            return Math.max(lower, Math.min(upper, value));
        }
    }
}
