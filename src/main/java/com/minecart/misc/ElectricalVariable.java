package com.minecart.misc;

import com.minecart.math.function.Variable;

public class ElectricalVariable extends Variable.DoubleVar{
    protected Type type;

    public ElectricalVariable(double lower, double upper, Type type) {
        super(lower, upper);
        this.type = type;
    }

    public ElectricalVariable(Type type){
        this.type = type;
    }

    public enum Type{
        CURRENT, VOLTAGE, OTHER;
    }
}
