package com.minecart.registry;

import com.minecart.behaviour.type.ElectricalInformation;
import com.minecart.component.CircuitNode;

import java.util.function.Function;
import java.util.function.Supplier;

public class ComponentType<T extends CircuitNode> {
    protected final String id;
    protected final Supplier<T> factory;

    protected ComponentType(String id, Supplier<T> factory){
        this.id = id;
        this.factory = factory;
    }

    public T create(){
        return factory.get();
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
