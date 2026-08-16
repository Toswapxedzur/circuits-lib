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

import java.util.List;

public class AllComponents {
    private static final double BJT_PORT_RADIUS = 0.44;
    private static final double BJT_PORT_X = BJT_PORT_RADIUS * 0.5;
    private static final double BJT_PORT_Y = BJT_PORT_RADIUS * Math.sqrt(3.0) * 0.5;

    /**
     * Bare {@link CircuitNode} for use only inside {@link CircuitComponent} ({@link CircuitElementType#isUnusual()}).
     */
    public static final CircuitElementType<CircuitNode> CIRCUIT_NODE =
            CircuitElementRegistry.register("circuit_node", CircuitNode::new, true);
    /**
     * Bare {@link CircuitEdge} for use only inside {@link CircuitComponent} ({@link CircuitElementType#isUnusual()}).
     *
     * <p>Unlike {@link #WIRE}, this is a true bare {@link CircuitEdge}: its {@code collectRule} stamps
     * <em>no</em> voltage law, so its branch current is a free variable determined solely by KCL plus
     * whatever constitutive relation a component imposes. This is exactly what the BJT collector branch
     * needs — an ideal current source whose current is set by {@code I_C = beta*I_B} while its voltage
     * floats. Registering it as {@code Wire::new} (a perfect wire that stamps {@code V_start-V_end=0})
     * added an extra equation, making any circuit containing a BJT over-determined / singular once the
     * component constitutive relation is collected.
     */
    public static final CircuitElementType<CircuitEdge> CIRCUIT_EDGE =
            CircuitElementRegistry.register("circuit_edge", CircuitEdge::new, true);
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
        // BJTransistor.generate() builds ports 0=base, 1=collector, 2=emitter. Keep them on the
        // transistor ring at roughly equal angular spacing: base left, collector top-right, emitter bottom-right.
        ComponentAnchorRegistry.register(BJ_TRANSISTOR, List.of(
                new ComponentAnchorRegistry.Anchor(0, -BJT_PORT_RADIUS, 0.0),
                new ComponentAnchorRegistry.Anchor(1, BJT_PORT_X, BJT_PORT_Y),
                new ComponentAnchorRegistry.Anchor(2, BJT_PORT_X, -BJT_PORT_Y)
        ));
    }

    /**
     * Force the JVM to run this class's {@code <clinit>}, which is where every built-in
     * {@link CircuitElementType} is registered with {@link CircuitElementRegistry}.
     *
     * <p>This method LOOKS like a no-op and is tempting to delete — don't. The whole point is the
     * <em>invocation</em>: per JLS §12.4.1, invoking a static method declared on a class triggers
     * that class's static field initializers. Without this, the registration side-effects in the
     * field initializers above never run, and {@link CircuitElementRegistry#getType(String)}
     * throws "Unknown component ID: ..." for any saved element on a cold start.
     *
     * <p>Historical trap: the equivalent {@code AllComponents.class} class-literal does
     * <strong>not</strong> trigger initialization (it only resolves the {@link Class} object), so an
     * earlier version of the bootstrap code silently failed to register anything when the first
     * action on a fresh JVM was loading an existing save.
     */
    public static void init() {
        // Intentionally empty. See javadoc.
    }
}
