package com.minecart.registry;

import com.minecart.action.ActionType;
import com.minecart.action.Action;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.World;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class CircuitElementType<T extends CircuitElement> {
    protected final String id;
    protected final Function<World, T> factory;
    protected final Map<ActionType<?>, BiConsumer<T, ? extends Action>> actionHandlers = new HashMap<>();

    protected CircuitElementType(String id, Function<World, T> factory){
        this.id = id;
        this.factory = factory;
    }

    public static <T extends CircuitElement> CircuitElementType<T> build(String id, Function<World, T> factory){
        return new CircuitElementType<T>(id, factory);
    }

    protected <A extends Action> void addActionHandler(ActionType<A> type, BiConsumer<T, A> handler) {
        actionHandlers.put(type, handler);
    }

    public <A extends Action> boolean perform(T element, ActionType<A> type, A action) {
        BiConsumer<T, A> handler = (BiConsumer<T, A>) actionHandlers.get(type);
        if (handler != null) {
            handler.accept(element, action);
            return true;
        }
        return false;
    }

    public T create(World world){
        return factory.apply(world);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
