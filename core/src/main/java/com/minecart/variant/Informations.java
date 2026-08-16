package com.minecart.variant;

import com.minecart.serialization.tag.CompoundTag;

public class Informations {
    public static final double DELTA = 1e-18;
    public static final double LARGE = 1e18;

    public static class BatteryInfo extends ElectricalInfo {
        private static final String TAG_VOLTAGE = "voltage";
        private static final String TAG_RESISTANCE = "resistance";

        protected double voltage;
        protected double resistance;

        public BatteryInfo(double voltage, double resistance) {
            this.voltage = voltage;
            this.resistance = resistance;
        }

        @Override
        public void save(CompoundTag tag) {
            tag.putDouble(TAG_VOLTAGE, voltage);
            tag.putDouble(TAG_RESISTANCE, resistance);
        }

        @Override
        public void load(CompoundTag tag) {
            voltage = tag.getDouble(TAG_VOLTAGE);
            resistance = tag.getDouble(TAG_RESISTANCE);
        }

        public double getVoltage() {
            return voltage;
        }

        public void setVoltage(double voltage) {
            this.voltage = voltage;
        }

        public double getResistance() {
            return resistance;
        }

        public void setResistance(double resistance) {
            this.resistance = resistance;
        }
    }

    public static class ResistorInfo extends ElectricalInfo {
        private static final String TAG_RESISTANCE = "resistance";

        protected double resistance;

        public ResistorInfo(double resistance) {
            this.setResistance(resistance);
        }

        @Override
        public void save(CompoundTag tag) {
            tag.putDouble(TAG_RESISTANCE, resistance);
        }

        @Override
        public void load(CompoundTag tag) {
            setResistance(tag.getDouble(TAG_RESISTANCE));
        }

        public double getResistance() {
            return resistance;
        }

        public void setResistance(double resistance) {
            this.resistance = Math.max(resistance, DELTA);
        }

        public double getConductance() {
            return 1.0 / this.resistance;
        }
    }

    public static class JunctionInfo extends ElectricalInfo {
        private static final String TAG_CONNECTION = "connection";

        protected int connection;

        public JunctionInfo(int connection) {
            this.connection = connection;
        }

        @Override
        public void save(CompoundTag tag) {
            tag.putInt(TAG_CONNECTION, connection);
        }

        @Override
        public void load(CompoundTag tag) {
            connection = tag.getInt(TAG_CONNECTION);
        }

        public int getConnection() {
            return connection;
        }

        public void setConnection(int connection) {
            this.connection = connection;
        }
    }

    public static class CapacitorInfo extends ElectricalInfo {
        private static final String TAG_CAPACITANCE = "capacitance";
        private static final String TAG_CHARGE = "charge";
        private static final String TAG_INTERNAL_RESISTANCE = "internal_resistance";

        protected double capacitance;
        protected double charge;
        protected double internalResistance;

        public CapacitorInfo(double capacitance, double resistance) {
            setCapacitance(capacitance);
            this.charge = 0.0;
            setInternalResistance(resistance);
        }

        @Override
        public void save(CompoundTag tag) {
            tag.putDouble(TAG_CAPACITANCE, capacitance);
            tag.putDouble(TAG_CHARGE, charge);
            tag.putDouble(TAG_INTERNAL_RESISTANCE, internalResistance);
        }

        @Override
        public void load(CompoundTag tag) {
            capacitance = tag.getDouble(TAG_CAPACITANCE);
            charge = tag.getDouble(TAG_CHARGE);
            internalResistance = tag.getDouble(TAG_INTERNAL_RESISTANCE);
        }

        public double getCapacitance() {
            return capacitance;
        }

        public void setCapacitance(double capacitance) {
            // Clamp to DELTA so voltage = charge / capacitance can never divide by zero and feed
            // Inf/NaN into the solver via Capacitor.collectRule (mirrors ResistorInfo/DiodeInfo/BJTInfo).
            this.capacitance = Math.max(capacitance, DELTA);
        }

        public double getInternalResistance() {
            return internalResistance;
        }

        public void setInternalResistance(double internalResistance) {
            // Clamp to DELTA for the same reason a resistor's resistance is clamped: a zero internal
            // resistance degenerates the capacitor's branch equation.
            this.internalResistance = Math.max(internalResistance, DELTA);
        }

        public double getCharge() {
            return charge;
        }

        public void setCharge(double charge) {
            this.charge = charge;
        }
    }

    /**
     * Idealized diode as a variable resistor: low forward resistance when current flows start→end, high reverse
     * resistance when current opposes that direction. {@link #effectiveResistance} is updated each tick from the
     * solved current; forward/reverse are the parameters.
     */
    public static class DiodeInfo extends ElectricalInfo {
        private static final String TAG_FORWARD = "forward_resistance";
        private static final String TAG_REVERSE = "reverse_resistance";

        protected double forwardResistance;
        protected double reverseResistance;
        /** Last applied resistance for the linearized relation (updated after each solve). */
        protected double effectiveResistance;

        public DiodeInfo(double forwardResistance, double reverseResistance) {
            setForwardResistance(forwardResistance);
            setReverseResistance(reverseResistance);
            this.effectiveResistance = this.forwardResistance;
        }

        @Override
        public void save(CompoundTag tag) {
            tag.putDouble(TAG_FORWARD, forwardResistance);
            tag.putDouble(TAG_REVERSE, reverseResistance);
        }

        @Override
        public void load(CompoundTag tag) {
            setForwardResistance(tag.getDouble(TAG_FORWARD));
            setReverseResistance(tag.getDouble(TAG_REVERSE));
            effectiveResistance = forwardResistance;
        }

        public double getForwardResistance() {
            return forwardResistance;
        }

        public void setForwardResistance(double forwardResistance) {
            this.forwardResistance = Math.max(forwardResistance, DELTA);
        }

        public double getReverseResistance() {
            return reverseResistance;
        }

        public void setReverseResistance(double reverseResistance) {
            this.reverseResistance = Math.max(reverseResistance, DELTA);
        }

        public double getEffectiveResistance() {
            return effectiveResistance;
        }

        public void setEffectiveResistance(double effectiveResistance) {
            this.effectiveResistance = Math.max(effectiveResistance, DELTA);
        }
    }

    /** Small-signal current gain β for {@link com.minecart.elements.component.BJTransistor}: {@code I_C = β I_B}. */
    public static class BJTInfo extends ElectricalInfo {
        private static final String TAG_BETA = "beta";

        protected double beta;

        public BJTInfo(double beta) {
            setBeta(beta);
        }

        @Override
        public void save(CompoundTag tag) {
            tag.putDouble(TAG_BETA, beta);
        }

        @Override
        public void load(CompoundTag tag) {
            setBeta(tag.getDouble(TAG_BETA));
        }

        public double getBeta() {
            return beta;
        }

        public void setBeta(double beta) {
            this.beta = Math.max(beta, DELTA);
        }
    }
}
