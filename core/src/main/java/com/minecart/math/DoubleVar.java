package com.minecart.math;

import java.util.UUID;

 /**
 * A mutable {@code double} value with a stable unique id. Holds a node's voltage or an edge's current:
 * ngspice writes solved values in, and rendering / sync / persistence read them back out.
 */
public class DoubleVar {
    protected final UUID id;
    protected double value = 0;

    protected DoubleVar() {
        this.id = UUID.randomUUID();
    }

    protected DoubleVar(UUID id, double value) {
        this.id = id;
        this.value = value;
    }

    public static DoubleVar create(){
        return new DoubleVar();
    }

    public static DoubleVar create(UUID id, double value){
         return new DoubleVar(id, value);
     }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }

    public UUID getUUID() {
        return id;
    }

}
