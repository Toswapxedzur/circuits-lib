package com.minecart.event.events;

import com.minecart.logic.ServerCircuit;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;

public abstract class ServerTickEvent extends Event{

    public enum Phase {
        PRE, POST
    }

    private final Phase phase;

    protected ServerTickEvent(Phase phase) {
        this.phase = phase;
    }

    public Phase getPhase() {
        return phase;
    }

    public static class Level extends ServerTickEvent {
        private final ServerLevel level;

        public Level(Phase phase, ServerLevel level) {
            super(phase);
            this.level = level;
        }
        public ServerLevel getLevel() { return level; }
    }

    public static class World extends ServerTickEvent {
        private final ServerWorld world;

        public World(Phase phase, ServerWorld world) {
            super(phase);
            this.world = world;
        }
        public ServerWorld getWorld() { return world; }
    }

    public static class Circuit extends ServerTickEvent {
        private final ServerCircuit circuit;

        public Circuit(Phase phase, ServerCircuit circuit) {
            super(phase);
            this.circuit = circuit;
        }
        public ServerCircuit getCircuit() { return circuit; }
    }
}
