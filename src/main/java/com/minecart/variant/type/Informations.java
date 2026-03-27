package com.minecart.variant.type;

public class Informations {
    public static class BatteryInfo extends ElectricalInfo {
        protected double voltage;

        protected double resistance;

        public BatteryInfo(double voltage, double resistance) {
            this.voltage = voltage;
            this.resistance = resistance;
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
        protected double resistance;

        public ResistorInfo(double resistance) {
            this.setResistance(resistance);
        }

        public double getResistance() {
            return resistance;
        }

        public void setResistance(double resistance) {
            this.resistance = Math.max(resistance, 1e-9);
        }

        public double getConductance() {
            return 1.0 / this.resistance;
        }
    }

    public static class JunctionInfo extends ElectricalInfo{
        protected int connection;

        public JunctionInfo(int connection){
            this.connection = connection;
        }

        public int getConnection() {
            return connection;
        }

        public void setConnection(int connection) {
            this.connection = connection;
        }
    }
    
    public static class CapacitorInfo extends ElectricalInfo{
        protected double capacitance;
        protected double charge;
        protected double internalResistance;

        public CapacitorInfo(double capacitance, double resistance){
            this.capacitance = capacitance;
            this.charge = 0.0;
            this.internalResistance = resistance;
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
