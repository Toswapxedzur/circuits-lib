package com.minecart.elements.edge;

import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllComponents;
import com.minecart.variant.Informations;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces H4: a 10V battery charging a capacitor (C=1, internal R=1, dt=0.05) must converge to
 * Q = C*V = +10 with the branch current decaying to zero. With the pre-fix {@code +R} sign in
 * {@link Capacitor#collectRule} the RC feedback inverts and the charge diverges negative instead.
 */
class CapacitorConvergenceTest {

    @Test
    void batteryChargesCapacitorTowardCVWithDecayingCurrent() {
        ServerLevel level = new ServerLevel();
        // dt = 0.05 is the default tick rate; assert it so the test's RC math is explicit.
        assertEquals(0.05, level.getTickRate(), 1e-12);
        ServerWorld world = level.createWorld();

        CircuitNode a = world.createNode(AllComponents.CONNECTION);
        CircuitNode b = world.createNode(AllComponents.CONNECTION);

        // Battery (a -> b): 10V EMF, near-ideal internal resistance.
        world.connect(AllComponents.BATTERY, a, b, new Informations.BatteryInfo(10.0, 1e-9));
        // Capacitor (a -> b): C = 1F, internal series resistance = 1 Ohm, starts uncharged (Q = 0).
        Capacitor cap = (Capacitor) world.connect(
                AllComponents.CAPACITOR, a, b, new Informations.CapacitorInfo(1.0, 1.0));

        double prevCharge = cap.get().getCharge();
        for (int i = 0; i < 2000; i++) {
            level.tick();
            double q = cap.get().getCharge();
            // Monotonic, bounded approach to +10 — never diverges negative (the pre-fix failure mode).
            assertTrue(q >= prevCharge - 1e-9,
                    "charge should climb monotonically toward +10, but dropped to " + q);
            assertTrue(q <= 10.0 + 1e-6, "charge overshot +10: " + q);
            prevCharge = q;
        }

        assertEquals(10.0, cap.get().getCharge(), 1e-3);
        assertEquals(0.0, cap.getCurrent().getValue(), 1e-3);
    }
}
