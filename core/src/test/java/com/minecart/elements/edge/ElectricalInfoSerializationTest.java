package com.minecart.elements.edge;

import com.minecart.registry.AllComponents;
import com.minecart.serialization.tag.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ElectricalInfoSerializationTest {

    @Test
    void batterySavesAndLoadsElectricalInfo() {
        Battery battery = new Battery(null);
        battery.setRegistryTypeId(AllComponents.BATTERY.getTypeId());
        battery.get().setVoltage(9.0);
        battery.get().setResistance(0.5);

        CompoundTag tag = new CompoundTag();
        battery.save(tag);

        Battery loaded = new Battery(null);
        loaded.setRegistryTypeId(AllComponents.BATTERY.getTypeId());
        loaded.load(tag);

        assertEquals(9.0, loaded.get().getVoltage(), 1e-9);
        assertEquals(0.5, loaded.get().getResistance(), 1e-9);
    }

    @Test
    void capacitorSavesAndLoadsElectricalInfo() {
        Capacitor capacitor = new Capacitor(null);
        capacitor.setRegistryTypeId(AllComponents.CAPACITOR.getTypeId());
        capacitor.get().setCapacitance(2.5);
        capacitor.get().setCharge(3.5);
        capacitor.get().setInternalResistance(0.2);

        CompoundTag tag = new CompoundTag();
        capacitor.save(tag);

        Capacitor loaded = new Capacitor(null);
        loaded.setRegistryTypeId(AllComponents.CAPACITOR.getTypeId());
        loaded.load(tag);

        assertEquals(2.5, loaded.get().getCapacitance(), 1e-9);
        assertEquals(3.5, loaded.get().getCharge(), 1e-9);
        assertEquals(0.2, loaded.get().getInternalResistance(), 1e-9);
    }
}
