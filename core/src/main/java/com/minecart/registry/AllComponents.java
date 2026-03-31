package com.minecart.registry;

import com.minecart.action.ActionTypes;
import com.minecart.component.Battery;
import com.minecart.component.Capacitor;
import com.minecart.component.Junction;
import com.minecart.component.Resistor;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitNode;

public class AllComponents {
    public static final CircuitElementType<CircuitNode> CONNECTION =
            CircuitElementRegistry.register("connection", world -> new CircuitNode(world));
    public static final CircuitElementType<CircuitComponent> CIRCUIT_COMPONENT =
            CircuitElementRegistry.register("circuit_component", world -> {
                CircuitComponent c = new CircuitComponent();
                c.setWorld(world);
                return c;
            });
    public static final CircuitElementType<Junction> JUNCTION =
            CircuitElementRegistry.register("junction", world -> new Junction(world));
    public static final CircuitElementType<Resistor> RESISTOR =
            CircuitElementRegistry.register("resistor", world -> new Resistor(world));
    public static final CircuitElementType<Battery> BATTERY =
            CircuitElementRegistry.register("battery", world -> new Battery(world));
    public static final CircuitElementType<Capacitor> CAPACITOR =
            CircuitElementRegistry.register("capacitor", world -> new Capacitor(world));

    static {
    }
}
