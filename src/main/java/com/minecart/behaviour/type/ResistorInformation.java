package com.minecart.behaviour.type;

public class ResistorInformation extends ElectricalInformation {
    public double resistance;

    public ResistorInformation() {
        this.resistance = 10.0;
    }

    public ResistorInformation(double resistance) {
        this.resistance = resistance;
    }
}