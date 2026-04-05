package com.minecart.client.events;

import com.minecart.client.logic.ClientCircuit;
import com.minecart.client.logic.ClientLevel;
import com.minecart.client.logic.ClientWorld;
import com.minecart.event.events.Event;

/**
 * Client-side frame tick, parallel to {@link com.minecart.event.events.ServerTickEvent}: PRE before client work,
 * POST after. Posted from {@link ClientLevel#tick()}, {@link ClientWorld#tick()}, and {@link ClientCircuit#clientTick()}.
 */
public abstract class ClientTickEvent extends Event {

    public enum Phase {
        PRE,
        POST
    }

    private final Phase phase;

    protected ClientTickEvent(Phase phase) {
        this.phase = phase;
    }

    public Phase getPhase() {
        return phase;
    }

    public static class Level extends ClientTickEvent {
        private final ClientLevel level;

        public Level(Phase phase, ClientLevel level) {
            super(phase);
            this.level = level;
        }

        public ClientLevel getLevel() {
            return level;
        }
    }

    public static class World extends ClientTickEvent {
        private final ClientWorld world;

        public World(Phase phase, ClientWorld world) {
            super(phase);
            this.world = world;
        }

        public ClientWorld getWorld() {
            return world;
        }
    }

    public static class Circuit extends ClientTickEvent {
        private final ClientCircuit circuit;

        public Circuit(Phase phase, ClientCircuit circuit) {
            super(phase);
            this.circuit = circuit;
        }

        public ClientCircuit getCircuit() {
            return circuit;
        }
    }
}
