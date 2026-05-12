package com.minecart.server.network;

import com.minecart.logic.ServerLevel;

import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/**
 * Drives {@link ServerLevel#tick()} on a dedicated thread at a fixed step derived from the level's
 * {@link ServerLevel#getTickRate()} (seconds per tick; 0.05 = 20 Hz, the default).
 * <p>
 * Each iteration: tick → sleep until the next deadline. If a tick overruns, the deadline is advanced so subsequent
 * ticks run back-to-back ("catch up") rather than skewing the wall-clock cadence further.
 * <p>
 * Lifecycle: {@link #start()} once, {@link #stop()} once. Not restartable.
 */
public class ServerTickThread {

    private final ServerLevel level;
    private final String name;
    private volatile boolean running;
    private Thread thread;

    public ServerTickThread(ServerLevel level) {
        this(level, "server-tick");
    }

    public ServerTickThread(ServerLevel level, String name) {
        this.level = Objects.requireNonNull(level, "level");
        this.name = Objects.requireNonNull(name, "name");
    }

    public synchronized void start() {
        if (thread != null) {
            throw new IllegalStateException("ServerTickThread already started");
        }
        running = true;
        thread = new Thread(this::loop, name);
        thread.setDaemon(false);
        thread.start();
    }

    public synchronized void stop() {
        if (thread == null) {
            return;
        }
        running = false;
        try {
            thread.join(2_000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        thread = null;
    }

    public boolean isRunning() {
        return running;
    }

    private void loop() {
        long stepNs = (long) (level.getTickRate() * 1_000_000_000d);
        if (stepNs <= 0) {
            stepNs = 50_000_000L; // 20 Hz fallback
        }
        long next = System.nanoTime();
        while (running) {
            try {
                level.tick();
            } catch (Throwable t) {
                t.printStackTrace();
            }
            next += stepNs;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                LockSupport.parkNanos(sleep);
            } else if (sleep < -stepNs * 5) {
                // Falling far behind; resync deadline rather than spiral.
                next = System.nanoTime();
            }
        }
    }
}
