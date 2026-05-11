package com.minecart.registry;

import com.minecart.elements.component.BJTransistor;
import com.minecart.elements.edge.Battery;
import com.minecart.elements.edge.Capacitor;
import com.minecart.elements.edge.Diode;
import com.minecart.elements.edge.Wire;
import com.minecart.elements.node.Junction;
import com.minecart.elements.edge.Resistor;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;

public class AllComponents {
    /**
     * Bare {@link CircuitNode} for use only inside {@link CircuitComponent} ({@link CircuitElementType#isUnusual()}).
     */
    public static final CircuitElementType<CircuitNode> CIRCUIT_NODE =
            CircuitElementRegistry.register("circuit_node", CircuitNode::new, true);
    /**
     * Bare {@link CircuitEdge} for use only inside {@link CircuitComponent} ({@link CircuitElementType#isUnusual()}).
     */
    public static final CircuitElementType<CircuitEdge> CIRCUIT_EDGE =
            CircuitElementRegistry.register("circuit_edge", Wire::new, true);
    /** Ideal wire (unusual).*/
    public static final CircuitElementType<Wire> WIRE =
            CircuitElementRegistry.register("wire", Wire::new, true);
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
    public static final CircuitElementType<BJTransistor> BJ_TRANSISTOR =
            CircuitElementRegistry.register("bj_transistor", world -> {
                BJTransistor b = new BJTransistor();
                b.setWorld(world);
                return b;
            });

    static {
    }
}
