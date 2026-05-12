package com.minecart.server.integrated;

import com.minecart.event.events.ServerTickEvent;
import com.minecart.event.info.InfoInjectors;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.logic.ServerLevel;
import com.minecart.protocol.codec.PayloadDecoder;
import com.minecart.protocol.codec.PayloadEncoder;
import com.minecart.protocol.payload.server.CircuitElementPayload;
import com.minecart.protocol.payload.server.CircuitSnapshotPayload;
import com.minecart.protocol.payload.server.WorldLifecyclePayload;
import com.minecart.server.listener.CircuitElementListener;
import com.minecart.server.network.ServerPayloadDispatcher;
import com.minecart.server.network.ServerTickThread;
import com.minecart.server.network.StandardServerHandlers;
import com.minecart.server.persistence.WorldStorage;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * In-process server backing the singleplayer "join world" flow: binds a {@link LocalServerChannel} at a unique
 * {@link LocalAddress}, registers all standard {@link com.minecart.protocol.payload.PayloadHandler}s on a
 * {@link ServerPayloadDispatcher}, and starts a {@link ServerTickThread} to drive {@link ServerLevel#tick()}.
 * <p>
 * The pipeline is identical to the dedicated server's: {@link PayloadDecoder} ↔ {@link PayloadEncoder} ↔
 * {@link ServerPayloadDispatcher}. Clients connect via {@link com.minecart.client.network.ClientConnection#connectIntegrated(LocalAddress)}.
 * <p>
 * Optional persistence via {@link #IntegratedServer(Path)}: the provided directory is loaded into the level on
 * {@link #start()} (skipped if no save file exists yet) and is the target of {@link #save()}.
 * <p>
 * Lifecycle: construct → {@link #start()} → {@link #address()} → … → {@link #stop()}. Not restartable.
 * <p>
 * Replication: {@link InfoInjectors} attaches default {@link com.minecart.variant.info.PositionInfo} /
 * {@link com.minecart.variant.info.RotationInfo}. A {@link CircuitElementListener} subscribes to element inserts
 * and removes; after each tick, pending deltas are flushed as
 * {@link com.minecart.protocol.payload.server.CircuitElementPayload}s to every connected channel. New
 * connections receive a {@link WorldLifecyclePayload}/{@link CircuitSnapshotPayload} catch-up so the client mirror
 * starts in sync with the authoritative state.
 */
public class IntegratedServer {

    private final ServerLevel level;
    private final LocalAddress address;
    private final ServerPayloadDispatcher dispatcher;
    private final ServerTickThread tickThread;
    /** Directory containing {@code level.dat}; {@code null} for in-memory only. */
    private final Path saveDir;

    private final ChannelGroup channels = new DefaultChannelGroup("integrated-server", GlobalEventExecutor.INSTANCE);
    private final CircuitElementListener elementListener;

    private EventLoopGroup loop;
    private Channel serverChannel;
    private boolean started;

    public IntegratedServer() {
        this(new ServerLevel(), null);
    }

    /** Loads from / saves to {@code saveDir} (if not null). */
    public IntegratedServer(Path saveDir) {
        this(new ServerLevel(), saveDir);
    }

    public IntegratedServer(ServerLevel level, Path saveDir) {
        this.level = Objects.requireNonNull(level, "level");
        this.address = new LocalAddress("singleplayer-" + UUID.randomUUID());
        this.dispatcher = new ServerPayloadDispatcher(level);
        StandardServerHandlers.register(dispatcher, level);
        this.tickThread = new ServerTickThread(level, "integrated-server-tick");
        this.saveDir = saveDir;
        Consumer<CircuitElementPayload> broadcastSink = payload -> {
            if (channels.isEmpty()) {
                return;
            }
            channels.writeAndFlush(payload);
        };
        this.elementListener = new CircuitElementListener(level, broadcastSink);
    }

    public ServerLevel level() {
        return level;
    }

    public LocalAddress address() {
        return address;
    }

    public ServerPayloadDispatcher dispatcher() {
        return dispatcher;
    }

    /** Save directory, or {@code null} for in-memory only. */
    public Path saveDir() {
        return saveDir;
    }

    /**
     * Loads from disk (if {@link #saveDir} is set and a save file exists), then binds the local channel and starts
     * the tick thread. Blocks until the channel is ready. Load errors are propagated and the server is not started.
     */
    public synchronized void start() throws InterruptedException, IOException {
        if (started) {
            throw new IllegalStateException("IntegratedServer already started");
        }
        // Default-info injection must be attached before any element exists, so loaded elements
        // get their PositionInfo/RotationInfo defaults applied before save data overrides them.
        InfoInjectors.attach(level);
        // Load BEFORE binding (no client can connect yet) and BEFORE starting tick (no concurrent mutation).
        if (saveDir != null) {
            WorldStorage.load(saveDir, level);
        }
        elementListener.attach();
        // Flush element deltas at the end of every tick so the next inbound payload from the client sees
        // an up-to-date mirror; runs on the tick thread because ServerTickEvent is posted from tick().
        level.register(ServerTickEvent.Level.class, evt -> {
            if (evt.getPhase() == ServerTickEvent.Phase.POST && evt.getLevel() == level) {
                elementListener.sync();
            }
        });
        loop = new DefaultEventLoopGroup(1, new DefaultThreadFactory("integrated-server-net", true));
        ServerBootstrap b = new ServerBootstrap()
                .group(loop)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        channels.add(ch);
                        ch.pipeline()
                                .addLast("decoder", new PayloadDecoder())
                                .addLast("encoder", new PayloadEncoder())
                                .addLast("dispatcher", dispatcher);
                        // Catch-up snapshot. Marshal onto the tick thread so reads of `level` are
                        // serialised against in-flight mutations.
                        level.submit(() -> sendInitialSnapshot(ch));
                    }
                });
        serverChannel = b.bind(address).sync().channel();
        tickThread.start();
        started = true;
    }

    /**
     * Send every existing world's lifecycle + circuit snapshots to a freshly connected channel so the client
     * mirror starts in sync. Runs on the tick thread; channel writes are queued onto the channel's I/O loop.
     */
    private void sendInitialSnapshot(Channel ch) {
        if (!ch.isActive()) {
            return;
        }
        for (World world : level.getWorlds()) {
            ch.writeAndFlush(WorldLifecyclePayload.insert(world.getId()));
            for (Circuit circuit : world.getCircuits()) {
                ch.writeAndFlush(CircuitSnapshotPayload.capture(world, circuit));
            }
        }
    }

    /**
     * Persists the current {@link ServerLevel} state to {@link #saveDir}. Submitted onto the tick thread so the
     * snapshot is consistent with whatever tick is currently in progress; the call returns immediately and the
     * IO happens between ticks. No-op if {@link #saveDir} is {@code null}.
     *
     * @throws IllegalStateException if the server is not started
     */
    public synchronized void save() {
        if (!started) {
            throw new IllegalStateException("IntegratedServer not started");
        }
        if (saveDir == null) {
            return;
        }
        level.submit(() -> {
            try {
                WorldStorage.save(level, saveDir);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * Stops the tick thread, closes the channel, and shuts down the event loop. Safe to call multiple times.
     * <p>
     * Does <strong>not</strong> automatically save — call {@link #save()} first if needed (and give the tick a
     * chance to drain the save runnable before stopping).
     */
    public synchronized void stop() {
        if (!started) {
            return;
        }
        try {
            tickThread.stop();
        } catch (Throwable ignored) {
        }
        try {
            elementListener.detach();
        } catch (Throwable ignored) {
        }
        try {
            channels.close().syncUninterruptibly();
        } catch (Throwable ignored) {
        }
        try {
            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
            }
        } finally {
            serverChannel = null;
            if (loop != null) {
                loop.shutdownGracefully();
                loop = null;
            }
            started = false;
        }
    }

    /**
     * Saves synchronously (blocks the calling thread until the snapshot is written) then stops. Suitable for
     * "Save & Quit" UI: the caller can show a spinner while this returns.
     */
    public synchronized void saveAndStop() throws IOException {
        if (!started) {
            return;
        }
        if (saveDir != null) {
            // Run on calling thread: writes are independent of any in-flight tick because we
            // copy nothing — we read the live level. Acceptable trade-off for shutdown.
            WorldStorage.save(level, saveDir);
        }
        stop();
    }

    public boolean isStarted() {
        return started;
    }
}
