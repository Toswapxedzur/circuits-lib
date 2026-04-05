package com.minecart.variant.type;

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
            this.capacitance = capacitance;
            this.charge = 0.0;
            this.internalResistance = resistance;
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
            this.capacitance = capacitance;
        }

        public double getInternalResistance() {
            return internalResistance;
        }

        public void setInternalResistance(double internalResistance) {
            this.internalResistance = internalResistance;
        }

        public double getCharge() {
            return charge;
        }

        public void setCharge(double charge) {
            this.charge = charge;
        }
    }
}
