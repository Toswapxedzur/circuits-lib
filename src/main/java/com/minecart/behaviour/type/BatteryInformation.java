package com.minecart.behaviour.type;

public class BatteryInformation extends ElectricalInformation {
    public double voltage;
    public double internalResistance;

    public BatteryInformation(double voltage, double internalResistance) {
        this.voltage = voltage;
        this.internalResistance = internalResistance;
    }

    public BatteryInformation() {
        this.voltage = 20.0;
        this.internalResistance = 1e-9;
    }
}