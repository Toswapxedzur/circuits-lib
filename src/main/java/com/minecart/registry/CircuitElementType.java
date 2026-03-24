package com.minecart.registry;

import com.minecart.action.ActionType;
import com.minecart.action.ElectricalAction;
import com.minecart.logic.CircuitElement;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public class CircuitElementType<T extends CircuitElement> {
    protected final String id;
    protected final Supplier<T> factory;
    protected final Map<ActionType<?>, BiConsumer<T, ? extends ElectricalAction>> actionHandlers = new HashMap<>();

    protected CircuitElementType(String id, Supplier<T> factory){
        this.id = id;
        this.factory = factory;
    }

    protected <A extends ElectricalAction> void addActionHandler(ActionType<A> type, BiConsumer<T, A> handler) {
        actionHandlers.put(type, handler);
    }

    public <A extends ElectricalAction> boolean perform(T element, ActionType<A> type, A action) {
        BiConsumer<T, A> handler = (BiConsumer<T, A>) actionHandlers.get(type);
        if (handler != null) {
            handler.accept(element, action);
            return true;
        }
        return false;
    }

    public T create(){
        return factory.get();
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
