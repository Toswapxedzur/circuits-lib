package com.minecart.variant;

import com.minecart.variant.type.ElectricalInformation;

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
