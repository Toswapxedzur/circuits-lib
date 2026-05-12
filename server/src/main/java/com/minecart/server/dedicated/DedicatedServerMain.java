package com.minecart.server.dedicated;

import com.minecart.logic.ServerLevel;
import com.minecart.protocol.codec.PayloadDecoder;
import com.minecart.protocol.codec.PayloadEncoder;
import com.minecart.server.network.ServerPayloadDispatcher;
import com.minecart.server.network.ServerTickThread;
import com.minecart.server.network.StandardServerHandlers;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * Headless server entry point. Binds a TCP {@link NioServerSocketChannel} on a port, runs the same pipeline as
 * {@link com.minecart.server.integrated.IntegratedServer}, and ticks the {@link ServerLevel} at the level's
 * configured rate. Run via {@code ./gradlew :server:run [port]} (defaults to port 25565).
 * <p>
 * Blocks the calling thread until the JVM is killed; the simulation runs on a separate {@link ServerTickThread}.
 */
public final class DedicatedServerMain {

    public static final int DEFAULT_PORT = 25565;

    private DedicatedServerMain() {}

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port '" + args[0] + "', falling back to " + DEFAULT_PORT);
            }
        }

        ServerLevel level = new ServerLevel();
        ServerPayloadDispatcher dispatcher = new ServerPayloadDispatcher(level);
        StandardServerHandlers.register(dispatcher, level);

        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup worker = new NioEventLoopGroup();
        ServerTickThread tick = new ServerTickThread(level);

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
                        }
                    })
                    .bind(port).sync().channel();
        } catch (Throwable t) {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
            throw t;
        }

        tick.start();
        System.out.println("Dedicated server listening on port " + port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down dedicated server...");
            try {
                tick.stop();
                serverChannel.close().syncUninterruptibly();
            } finally {
                worker.shutdownGracefully();
                boss.shutdownGracefully();
            }
        }, "dedicated-shutdown"));

        // Block on channel close; tick thread keeps the JVM alive otherwise too.
        serverChannel.closeFuture().sync();
    }
}
