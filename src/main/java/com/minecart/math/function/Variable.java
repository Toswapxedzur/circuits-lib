package com.minecart.math.function;

public class Variable<T> {
    protected final String name;

    protected int index;

    public Variable(int index, String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public static class DoubleVar extends Variable<Double> {
        private double lower;
        private double upper;

        /**
         * Creates a bounded double variable.
         */
        public DoubleVar(int index, String name, double lower, double upper) {
            super(index, name);
            this.lower = lower;
            this.upper = upper;
        }

        /**
         * Creates an unbounded double variable.
         */
        public DoubleVar(int index, String name) {
            this(index, name, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
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
