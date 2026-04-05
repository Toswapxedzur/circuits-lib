package com.minecart.registry;

import com.minecart.elements.edge.Battery;
import com.minecart.elements.edge.Capacitor;
import com.minecart.elements.edge.Diode;
import com.minecart.elements.node.Junction;
import com.minecart.elements.edge.Resistor;
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
    public static final CircuitElementType<Diode> DIODE =
            CircuitElementRegistry.register("diode", world -> new Diode(world));

    static {
    }
}
