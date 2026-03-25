package com.minecart.variant;

import com.minecart.variant.type.ElectricalInfo;

import java.io.Serializable;

/**
 * Represent an information that an electrical component held, only added after creation
 * @param <O> Type of electrical information
 */
public interface ElectricalVariate<O extends ElectricalInfo> extends Serializable {
    void set(O argument);

    default void setDefault(){
        set(getDefault());
    }

    O get();

    O getDefault();

    boolean hasProperty(int index);

    Object getProperty(int index);
}
