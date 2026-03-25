package com.minecart.registry;

import com.minecart.action.ActionTypes;
import com.minecart.component.Battery;
import com.minecart.component.Junction;
import com.minecart.component.Resistor;
import com.minecart.logic.CircuitNode;

public class AllComponents {
    public static final CircuitElementType<CircuitNode> CONNECTION = CircuitElementType.build("connection", world->new CircuitNode(world));
    public static final CircuitElementType<Junction> JUNCTION = CircuitElementType.build("junction", world->new Junction(world));
    public static final CircuitElementType<Resistor> RESISTOR = CircuitElementType.build("resistor", world->new Resistor(world));
    public static final CircuitElementType<Battery> BATTERY = CircuitElementType.build("battery", world->new Battery(world));

    static {
        BATTERY.addActionHandler(ActionTypes.SET_RESISTANCE, (battery, action) -> battery.handleResistance(action));
        BATTERY.addActionHandler(ActionTypes.SET_VOLTAGE, (battery, action) -> battery.handleVoltage(action));

        RESISTOR.addActionHandler(ActionTypes.SET_RESISTANCE, (resistor, action) -> resistor.handleResistance(action));

        JUNCTION.addActionHandler(ActionTypes.SET_CONNECTION, (junction, action) -> junction.handleConnection(action));
    }
}
