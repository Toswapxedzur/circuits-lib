package com.minecart.logic.behaviour;

public interface ElectricalVariate<I extends ElectricalArgument, O extends ElectricalInformation> {
    void set(I argument);

    O get();
}
