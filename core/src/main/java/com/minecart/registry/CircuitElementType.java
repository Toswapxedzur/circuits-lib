package com.minecart.registry;

import com.minecart.action.ActionType;
import com.minecart.action.Action;
import com.minecart.logic.CircuitElement;
import com.minecart.foundation.World;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class CircuitElementType<T extends CircuitElement> {
    protected final String id;
    protected final Function<World, T> factory;
    /**
     * When {@code true}, instances may not be created via world graph APIs ({@link com.minecart.logic.ServerWorld#createNode},
     * {@link com.minecart.logic.ServerWorld#connect}) or {@link #create(World)}; use
     * {@link com.minecart.logic.ServerWorld#createNodeForComponent} / {@link com.minecart.logic.ServerWorld#connectInComponent}
     * from {@link com.minecart.logic.CircuitComponent} instead. Tag deserialization uses {@link #create(World, boolean)} with
     * {@code allowUnusual true}.
     */
    protected final boolean unusual;
    protected final Map<ActionType<?>, BiConsumer<T, ? extends Action>> actionHandlers = new HashMap<>();

    protected CircuitElementType(String id, Function<World, T> factory) {
        this(id, factory, false);
    }

    protected CircuitElementType(String id, Function<World, T> factory, boolean unusual) {
        this.id = id;
        this.factory = factory;
        this.unusual = unusual;
    }

    public static <T extends CircuitElement> CircuitElementType<T> build(String id, Function<World, T> factory) {
        return new CircuitElementType<>(id, factory);
    }

    public static <T extends CircuitElement> CircuitElementType<T> build(
            String id, Function<World, T> factory, boolean unusual) {
        return new CircuitElementType<>(id, factory, unusual);
    }

    public boolean isUnusual() {
        return unusual;
    }

    public <A extends Action> void addActionHandler(ActionType<A> type, BiConsumer<T, A> handler) {
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

    /**
     * Dispatches using {@link Action#getActionType()} to find the handler.
     */
    @SuppressWarnings("unchecked")
    public boolean perform(T element, Action action) {
        ActionType<?> type = action.getActionType();
        BiConsumer<T, Action> handler = (BiConsumer<T, Action>) actionHandlers.get(type);
        if (handler != null) {
            handler.accept(element, action);
            return true;
        }
        return false;
    }

    /**
     * Creates an element for normal world graph use; fails for {@link #isUnusual()} types.
     */
    public T create(World world) {
        return create(world, false);
    }

    /**
     * @param allowUnusual pass {@code true} only from {@link com.minecart.logic.CircuitComponent} scaffolding,
     *                     tag deserialization, or other controlled paths.
     */
    public T create(World world, boolean allowUnusual) {
        if (unusual && !allowUnusual) {
            throw new IllegalStateException(
                    "Unusual element type '"
                            + id
                            + "' must be created from a CircuitComponent (newNode/newEdge) or via deserialization, "
                            + "not via world createNode/connect");
        }
        T t = factory.apply(world);
        if (t instanceof CircuitElement ce) {
            ce.setRegistryTypeId(id);
        }
        return t;
    }

    /** Registry string id (e.g. {@code "resistor"}). */
    public String getTypeId() {
        return id;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
