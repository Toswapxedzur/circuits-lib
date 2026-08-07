package com.minecart.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DoubleVarTest {

    @Test
    void createDefaultsToZero() {
        assertEquals(0.0, DoubleVar.create().getValue(), 0.0);
    }
}
