package com.minecart.elements.edge;

import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.logic.CircuitNode;
import com.minecart.registry.AllComponents;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.variant.Informations;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiodeTest {

    @Test
    void tick_setsEffectiveResistanceFromCurrentSign() {
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode n1 = w.createNode(AllComponents.CONNECTION);
        CircuitNode n2 = w.createNode(AllComponents.CONNECTION);
        Diode d = (Diode) w.connect(AllComponents.DIODE, n1, n2);

        d.get().setForwardResistance(2.0);
        d.get().setReverseResistance(1e6);

        d.getCurrent().setValue(0.5);
        d.tick();
        assertEquals(2.0, d.get().getEffectiveResistance(), 1e-9);

        d.getCurrent().setValue(-0.1);
        d.tick();
        assertEquals(1e6, d.get().getEffectiveResistance(), 1.0);

        d.getCurrent().setValue(0.0);
        d.tick();
        assertEquals(2.0, d.get().getEffectiveResistance(), 1e-9);
    }

    @Test
    void diodeInfo_roundTripTag() {
        var info = new Informations.DiodeInfo(5.0, Informations.LARGE);
        var tag = new CompoundTag();
        info.save(tag);
        var loaded = new Informations.DiodeInfo(0.1, 0.2);
        loaded.load(tag);
        assertEquals(5.0, loaded.getForwardResistance(), 1e-12);
        assertEquals(Informations.LARGE, loaded.getReverseResistance(), 1e-12);
        assertEquals(5.0, loaded.getEffectiveResistance(), 1e-12);
    }
}
