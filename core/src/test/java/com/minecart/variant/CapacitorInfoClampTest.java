package com.minecart.variant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces M6/B7: {@link com.minecart.variant.Informations.CapacitorInfo} must clamp capacitance
 * and internal resistance to {@link Informations#DELTA}, so {@code voltage = charge / capacitance}
 * can never produce Inf/NaN that would poison the solver via {@code Capacitor.collectRule}.
 */
class CapacitorInfoClampTest {

    @Test
    void setterClampsZeroCapacitanceToDelta() {
        Informations.CapacitorInfo info = new Informations.CapacitorInfo(1.0, 1.0);
        info.setCapacitance(0.0);
        assertTrue(info.getCapacitance() >= Informations.DELTA,
                "capacitance should clamp to DELTA, was " + info.getCapacitance());

        info.setCharge(5.0);
        double voltage = info.getCharge() / info.getCapacitance();
        assertTrue(Double.isFinite(voltage), "charge/capacitance must be finite, was " + voltage);
    }

    @Test
    void setterClampsZeroInternalResistanceToDelta() {
        Informations.CapacitorInfo info = new Informations.CapacitorInfo(1.0, 1.0);
        info.setInternalResistance(0.0);
        assertTrue(info.getInternalResistance() >= Informations.DELTA,
                "internal resistance should clamp to DELTA, was " + info.getInternalResistance());
    }

    @Test
    void constructorClampsZeroArguments() {
        Informations.CapacitorInfo info = new Informations.CapacitorInfo(0.0, 0.0);
        assertTrue(info.getCapacitance() >= Informations.DELTA);
        assertTrue(info.getInternalResistance() >= Informations.DELTA);
    }
}
