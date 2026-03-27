package com.minecart.action;

public class ActionTypes {
    public static final ActionType<Actions.SetResistanceAction> SET_RESISTANCE = new ActionType<>("set_resistance");
    public static final ActionType<Actions.SetVoltageAction> SET_VOLTAGE = new ActionType<>("set_voltage");
    public static final ActionType<Actions.SetConnectionAction> SET_CONNECTION = new ActionType<>("set_connection");
    public static final ActionType<Actions.SetCapacitanceAction> SET_CAPACITANCE = new ActionType<>("set_capacitance");
}
