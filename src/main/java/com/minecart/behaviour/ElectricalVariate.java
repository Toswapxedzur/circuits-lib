package com.minecart.behaviour;

import com.minecart.behaviour.type.ElectricalInformation;

import java.io.Serializable;

public interface ElectricalVariate<O extends ElectricalInformation> extends Serializable {
    void set(O argument);

    default void setDefault(){
        set(getDefault());
    }

    O get();

    O getDefault();

    boolean hasProperty(int index);

    Object getProperty(int index);
}
