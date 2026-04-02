package com.minecart.action;

import java.util.function.Supplier;

/**
 * Built-in {@link Action} kinds: each {@link ActionType} is constructed with a factory and registered.
 */
public final class ActionTypes {

    public static final ActionType<Actions.SetVoltageAction> SET_VOLTAGE;
    public static final ActionType<Actions.SetResistanceAction> SET_RESISTANCE;
    public static final ActionType<Actions.SetConnectionAction> SET_CONNECTION;
    public static final ActionType<Actions.SetCapacitanceAction> SET_CAPACITANCE;

    static {
        SET_VOLTAGE = create("set_voltage", Actions.SetVoltageAction::new);
        SET_RESISTANCE = create("set_resistance", Actions.SetResistanceAction::new);
        SET_CONNECTION = create("set_connection", Actions.SetConnectionAction::new);
        SET_CAPACITANCE = create("set_capacitance", Actions.SetCapacitanceAction::new);
    }

    protected static <T extends Action> ActionType<T> create(String id, Supplier<T> factory) {
        ActionType<T> actionType = new ActionType<>(id, factory);
        ActionRegistry.register(actionType);
        return actionType;
    }

    private ActionTypes() {
    }
}
