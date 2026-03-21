package com.minecart.registry;

import com.minecart.component.Battery;
import com.minecart.component.Junction;
import com.minecart.component.Resistor;

public class AllComponents {
    public static final ComponentType<Junction> JUNCTION = ComponentRegistry.register("junction", Junction::new);
    public static final ComponentType<Battery> BATTERY = ComponentRegistry.register("battery", Battery::new);
    public static final ComponentType<Resistor> RESISTOR = ComponentRegistry.register("resistor", Resistor::new);
}
