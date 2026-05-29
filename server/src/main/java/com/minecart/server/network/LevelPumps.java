package com.minecart.server.network;

import com.minecart.logic.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives the server's simulation off a single worker thread, scheduling two independent pump
 * tasks onto a {@link ScheduledExecutorService}:
 *
 * <ul>
 *   <li><b>Main tick</b> ({@link ServerLevel#getTickRate()} seconds, default 50 ms / 20 Hz):
 *       drains the action queue, flushes the drag aggregator (safety-net flush — usually empty
 *       because the drag pump already drained it), runs each world's per-tick simulation, fires
 *       {@code preTick} / {@code postTick} events. The {@code postTick} listeners do the
 *       circuit-lifecycle + element-delta network sync.</li>
 *   <li><b>Drag pump</b> ({@link #DRAG_PUMP_PERIOD_MS} ms, ~250 Hz): drains the action queue
 *       (so {@code level.submit(...)} actions from netty handlers run within a few ms instead of
 *       waiting up to a full tick), then flushes the drag aggregator. Optionally invokes a
 *       caller-supplied {@code postDragWork} runnable after the flush — the integrated server
 *       uses this to push fresh authoritative poses to clients between main ticks, so the
 *       cursor-to-element latency is decoupled from the 20 Hz logic tick.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Both pumps run on the same single-thread executor, so they serialise naturally with each other
 * and with {@code level.submit}-ed actions (those drain inside the pump bodies, on the same
 * thread). The world model is therefore touched by exactly one thread, preserving the
 * single-threaded invariant the level / world classes rely on. Netty I/O threads invoke
 * {@code level.submit(...)} concurrently, but {@link ServerLevel#submit} only enqueues onto a
 * {@code ConcurrentLinkedQueue} — actual execution is on the worker thread.
 *
 * <h2>Lifecycle</h2>
 * Construct → {@link #start()} once → {@link #stop()} once. Not restartable. {@link #stop()} is
 * safe to call from any thread and from inside a pump task (e.g. an exception handler).
 */
public final class LevelPumps {

    private static final Logger log = LoggerFactory.getLogger(LevelPumps.class);

    /**
     * Period of the high-frequency drag pump. Picked so the perceived input-to-broadcast latency
     * is well below one render frame at 60 fps (16 ms) and well below the 20 Hz main-tick period
     * (50 ms), while staying coarse enough that the empty-buffer fast path doesn't dominate CPU.
     */
    private static final long DRAG_PUMP_PERIOD_MS = 4L;

    private final ServerLevel level;
    private final String threadName;
    private final Runnable postDragWork;

    private ScheduledExecutorService exec;
    private ScheduledFuture<?> mainTickFuture;
    private ScheduledFuture<?> dragPumpFuture;
    private volatile boolean running;

    public LevelPumps(ServerLevel level, String threadName) {
        this(level, threadName, () -> {});
    }

    /**
     * @param postDragWork ran on the worker thread after each drag-pump flush. Use for pushing
     *                     replication deltas accumulated by the flush to clients between ticks.
     *                     May be a no-op runnable when not needed (e.g. headless dedicated server
     *                     without replication listeners attached).
     */
    public LevelPumps(ServerLevel level, String threadName, Runnable postDragWork) {
        this.level = Objects.requireNonNull(level, "level");
        this.threadName = Objects.requireNonNull(threadName, "threadName");
        this.postDragWork = Objects.requireNonNull(postDragWork, "postDragWork");
    }

    public synchronized void start() {
        if (exec != null) {
            throw new IllegalStateException("LevelPumps already started");
        }
        long mainTickPeriodMs = (long) Math.max(1.0, level.getTickRate() * 1000.0);
        AtomicLong threadCounter = new AtomicLong();
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName + (threadCounter.get() == 0 ? "" : "-" + threadCounter.get()));
            threadCounter.incrementAndGet();
            t.setDaemon(false);
            return t;
        });
        running = true;
        // scheduleAtFixedRate semantics: if a task overruns, the next runs immediately after
        // ("catch up") rather than skewing the wall-clock cadence further.
        mainTickFuture = exec.scheduleAtFixedRate(this::mainTick,
                mainTickPeriodMs, mainTickPeriodMs, TimeUnit.MILLISECONDS);
        dragPumpFuture = exec.scheduleAtFixedRate(this::dragPump,
                DRAG_PUMP_PERIOD_MS, DRAG_PUMP_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (exec == null) {
            return;
        }
        running = false;
        if (mainTickFuture != null) {
            mainTickFuture.cancel(false);
            mainTickFuture = null;
        }
        if (dragPumpFuture != null) {
            dragPumpFuture.cancel(false);
            dragPumpFuture = null;
        }
        exec.shutdown();
        try {
            if (!exec.awaitTermination(2, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exec.shutdownNow();
        }
        exec = null;
    }

    public boolean isRunning() {
        return running;
    }

    private void mainTick() {
        if (!running) {
            return;
        }
        try {
            level.tick();
        } catch (Throwable t) {
            // Log and continue: a throwing task on scheduleAtFixedRate would otherwise be
            // silently cancelled, and we want the server to keep ticking through transient errors.
            log.error("Main tick failed; continuing", t);
        }
    }

    private void dragPump() {
        if (!running) {
            return;
        }
        try {
            // Drain submit()-ed actions first so any MoveElementPayload handlers waiting in the
            // action queue get a chance to enqueue their gesture into the aggregator before the
            // flush below. Without this, a payload that arrived 1 ms ago would sit in the queue
            // for up to the next main-tick period.
            level.drainActionQueue();
            level.getDragAggregator().flush(level);
            postDragWork.run();
        } catch (Throwable t) {
            log.error("Drag pump failed; continuing", t);
        }
    }
}
