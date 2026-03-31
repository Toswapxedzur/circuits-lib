package com.minecart.math;

import java.util.UUID;

 /**
 * Representing a mutable reference that has an unique id and a temporary index
 * @param <T> Type
 */
public class DoubleVar {
    protected final UUID id;
    protected int index;
    protected double value = -1;

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

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

}
