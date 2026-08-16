package com.minecart.server.dedicated;

import com.minecart.event.events.ServerTickEvent;
import com.minecart.event.info.InfoInjectors;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.World;
import com.minecart.logic.ServerLevel;
import com.minecart.protocol.codec.PayloadDecoder;
import com.minecart.protocol.codec.PayloadEncoder;
import com.minecart.protocol.payload.AllPayloads;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.server.CircuitElementPayload;
import com.minecart.protocol.payload.server.CircuitLifecyclePayload;
import com.minecart.protocol.payload.server.CircuitSnapshotPayload;
import com.minecart.protocol.payload.server.WorldLifecyclePayload;
import com.minecart.registry.AllComponents;
import com.minecart.registry.AllElementInfos;
import com.minecart.server.listener.CircuitElementListener;
import com.minecart.server.listener.CircuitLifecycleListener;
import com.minecart.server.network.LevelPumps;
import com.minecart.server.network.ServerPayloadDispatcher;
import com.minecart.server.network.StandardServerHandlers;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Headless server entry point. Binds a TCP {@link NioServerSocketChannel} on a port, runs the same pipeline as
 * {@link com.minecart.server.integrated.IntegratedServer}, and ticks the {@link ServerLevel} at the level's
 * configured rate. Run via {@code ./gradlew :server:run [port]} (defaults to port 25565).
 * <p>
 * Blocks the calling thread until the JVM is killed; the simulation runs on a separate worker thread
 * driven by {@link LevelPumps}.
 * <p>
 * Replication mirrors {@link com.minecart.server.integrated.IntegratedServer}: a {@link ChannelGroup} tracks
 * connected clients, a {@link CircuitElementListener} / {@link CircuitLifecycleListener} accumulate per-tick
 * deltas that are broadcast to that group, and each freshly connected client receives a full-world snapshot
 * before it joins the broadcast group so its mirror starts in sync.
 */
public final class DedicatedServerMain {

    public static final int DEFAULT_PORT = 25565;

    private static final Logger log = LoggerFactory.getLogger(DedicatedServerMain.class);

    private DedicatedServerMain() {}

    public static void main(String[] args) throws Exception {
        // Pin Netty's logging backend before any Netty class is touched (the static initializer of
        // InternalLoggerFactory caches whatever provider is configured at first reference). Setting
        // this explicitly removes the implicit "find SLF4J on classpath" detection step.
        InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);

        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                log.warn("Invalid port '{}', falling back to {}", args[0], DEFAULT_PORT);
            }
        }

        // Bootstrap the registries BEFORE anything binds or ticks — identical to IntegratedServer.start().
        // AllComponents/AllElementInfos register element + info types; AllPayloads forces every Payload
        // subclass's <clinit> so the decoder can resolve inbound wire ids. Skipping these left a connected
        // client's ClientLevel empty and dropped inbound payloads on an "unknown id" decode error.
        AllComponents.init();
        AllElementInfos.init();
        AllPayloads.init();

        ServerLevel level = new ServerLevel();
        // Same rationale as IntegratedServer.start(): the dispatcher routes ElementInfoUpdateEvents
        // to the per-type save handlers element classes register in their <clinit>.
        com.minecart.ui.panel.InfoPanelRegistry.installLevelListener(level);
        // Default-info injection must be attached before any element exists so created elements get their
        // PositionInfo/RotationInfo defaults.
        InfoInjectors.attach(level);
        level.ensureDefaultWorld();

        // Replication wiring: a group of connected client channels plus the two delta listeners. The sinks
        // write to the group (no-op while empty) exactly like IntegratedServer's broadcast sinks.
        ChannelGroup channels = new DefaultChannelGroup("dedicated-server", GlobalEventExecutor.INSTANCE);
        Consumer<Payload> broadcast = payload -> {
            if (!channels.isEmpty()) {
                channels.writeAndFlush(payload);
            }
        };
        Consumer<CircuitElementPayload> elementSink = payload -> {
            if (!channels.isEmpty()) {
                channels.writeAndFlush(payload);
            }
        };
        Consumer<CircuitLifecyclePayload> lifecycleSink = payload -> {
            if (!channels.isEmpty()) {
                channels.writeAndFlush(payload);
            }
        };
        CircuitElementListener elementListener = new CircuitElementListener(level, elementSink);
        CircuitLifecycleListener lifecycleListener = new CircuitLifecycleListener(level, lifecycleSink);
        // Attach AFTER ensureDefaultWorld so the default world's circuits don't emit spurious lifecycle
        // INSERTs — new clients learn about them via the initial snapshot instead.
        lifecycleListener.attach();
        elementListener.attach();

        // Order matters (see IntegratedServer.pushReplicationDeltas): announce new circuits, apply element
        // deltas, then drop dead circuits.
        Runnable pushDeltas = () -> {
            if (!lifecycleListener.isAttached() && !elementListener.isAttached()) {
                return;
            }
            lifecycleListener.syncInserts();
            elementListener.sync();
            lifecycleListener.syncRemoves();
        };
        // Flush deltas at the end of every main tick, and (via LevelPumps postDragWork below) between ticks.
        level.register(ServerTickEvent.Level.class, evt -> {
            if (evt.getPhase() == ServerTickEvent.Phase.POST && evt.getLevel() == level) {
                pushDeltas.run();
            }
        });

        ServerPayloadDispatcher dispatcher = new ServerPayloadDispatcher(level);
        StandardServerHandlers.register(dispatcher, level, broadcast);

        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup worker = new NioEventLoopGroup();
        // Push replication deltas between main ticks too, so drag/edit broadcasts reach clients promptly.
        LevelPumps pumps = new LevelPumps(level, "dedicated-server-tick", pushDeltas);

        Channel serverChannel;
        try {
            serverChannel = new ServerBootstrap()
                    .group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline()
                                    .addLast("decoder", new PayloadDecoder())
                                    .addLast("encoder", new PayloadEncoder())
                                    .addLast("dispatcher", dispatcher);
                            // Snapshot-before-registration on the tick thread: send the full world state to
                            // this channel FIRST, then add it to the broadcast group, so it can never receive
                            // a delta for a circuit it hasn't been told about yet (same invariant as
                            // IntegratedServer). Both the snapshot writes and later broadcasts run on the
                            // tick thread, so the queued snapshot precedes any broadcast to this channel.
                            level.submit(() -> {
                                if (!ch.isActive()) {
                                    return;
                                }
                                sendInitialSnapshot(level, ch);
                                channels.add(ch);
                            });
                        }
                    })
                    .bind(port).sync().channel();
        } catch (Throwable t) {
            elementListener.detach();
            lifecycleListener.detach();
            boss.shutdownGracefully();
            worker.shutdownGracefully();
            throw t;
        }

        pumps.start();
        log.info("Dedicated server listening on port {}", port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down dedicated server...");
            try {
                pumps.stop();
                elementListener.detach();
                lifecycleListener.detach();
                channels.close().syncUninterruptibly();
                serverChannel.close().syncUninterruptibly();
            } finally {
                worker.shutdownGracefully();
                boss.shutdownGracefully();
            }
        }, "dedicated-shutdown"));

        // Block on channel close; tick thread keeps the JVM alive otherwise too.
        serverChannel.closeFuture().sync();
    }

    /**
     * Sends every existing world's lifecycle + circuit snapshots to a freshly connected channel so the client
     * mirror starts in sync. Mirrors {@code IntegratedServer.sendInitialSnapshot}. Runs on the tick thread;
     * channel writes are queued onto the channel's I/O loop.
     */
    private static void sendInitialSnapshot(ServerLevel level, Channel ch) {
        if (!ch.isActive()) {
            return;
        }
        for (World world : level.getWorlds()) {
            ch.writeAndFlush(WorldLifecyclePayload.insert(world.getId(), world.getName()));
            for (Circuit circuit : world.getCircuits()) {
                ch.writeAndFlush(CircuitSnapshotPayload.capture(world, circuit));
            }
        }
    }
}
