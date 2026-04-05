package com.minecart.event.events;

import com.minecart.logic.ServerCircuit;

public abstract class ServerTickEvent extends Event {

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
        private final com.minecart.foundation.Level level;

        public Level(Phase phase, com.minecart.foundation.Level level) {
            super(phase);
            this.level = level;
        }

        public com.minecart.foundation.Level getLevel() {
            return level;
        }
    }

    public static class World extends ServerTickEvent {
        private final com.minecart.foundation.World world;

        public World(Phase phase, com.minecart.foundation.World world) {
            super(phase);
            this.world = world;
        }

        public com.minecart.foundation.World getWorld() {
            return world;
        }
    }

    public static class Circuit extends ServerTickEvent {
        private final ServerCircuit circuit;

        public Circuit(Phase phase, ServerCircuit circuit) {
            super(phase);
            this.circuit = circuit;
        }

        public ServerCircuit getCircuit() {
            return circuit;
        }
    }
}
