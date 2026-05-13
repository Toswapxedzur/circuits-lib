package com.minecart.server.integrated;

import com.minecart.misc.CoreStrings;
import com.minecart.protocol.codec.PayloadDecoder;
import com.minecart.protocol.codec.PayloadEncoder;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.client.ConnectEdgePayload;
import com.minecart.protocol.payload.client.PlaceComponentPayload;
import com.minecart.protocol.payload.client.PlaceNodePayload;
import com.minecart.protocol.payload.server.CircuitElementChange;
import com.minecart.protocol.payload.server.CircuitElementPayload;
import com.minecart.protocol.payload.server.CircuitSnapshotPayload;
import com.minecart.protocol.payload.server.WorldLifecyclePayload;
import com.minecart.registry.AllComponents;
import com.minecart.registry.AllElementInfos;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.Tag;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage that the three new SERVER-bound placement payloads cause the matching state mutation on
 * {@link IntegratedServer}'s authoritative level and that the resulting insert is replicated back as a
 * {@link CircuitElementPayload}. Drives the full Netty {@link io.netty.channel.local.LocalChannel} pipeline
 * (decoder/encoder + dispatcher + tick thread + replication broadcast) so the test catches breakages at any
 * layer.
 */
class PlacementPayloadRoundTripTest {

    private IntegratedServer server;
    private EventLoopGroup loop;
    private Channel ch;
    private BlockingQueue<Payload> inbound;

    @BeforeEach
    void setUp() throws Exception {
        server = new IntegratedServer();
        server.start();
        // Pre-create one world so the placement payloads have a known target. submit + wait via tick.
        CountDownLatch worldReady = new CountDownLatch(1);
        server.level().submit(() -> {
            server.level().createWorld();
            worldReady.countDown();
        });
        assertTrue(worldReady.await(2, TimeUnit.SECONDS), "World creation should run within 2s");

        inbound = new LinkedBlockingQueue<>();
        loop = new DefaultEventLoopGroup(1);
        ch = new Bootstrap()
                .group(loop)
                .channel(LocalChannel.class)
                .handler(new ChannelInitializer<>() {
                    @Override protected void initChannel(Channel c) {
                        c.pipeline()
                                .addLast(new PayloadDecoder())
                                .addLast(new PayloadEncoder())
                                .addLast(new SimpleChannelInboundHandler<Payload>() {
                                    @Override protected void channelRead0(ChannelHandlerContext ctx, Payload msg) {
                                        inbound.offer(msg);
                                    }
                                });
                    }
                })
                .connect(server.address()).sync().channel();

        // Drain the catch-up snapshot (one WorldLifecyclePayload + zero or more CircuitSnapshotPayloads).
        // It can take a tick or two for the dispatcher's submit() to run.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        boolean sawWorld = false;
        while (System.nanoTime() < deadline) {
            Payload p = inbound.poll(100, TimeUnit.MILLISECONDS);
            if (p == null) {
                if (sawWorld) break;
                continue;
            }
            if (p instanceof WorldLifecyclePayload) {
                sawWorld = true;
            }
            // Drop snapshots for empty worlds — there are none yet on this server.
        }
        assertTrue(sawWorld, "Expected initial WorldLifecyclePayload from server within 2s");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (ch != null && ch.isActive()) ch.close().sync();
        if (loop != null) loop.shutdownGracefully();
        if (server != null) server.stop();
    }

    private UUID firstWorldId() {
        return server.level().getWorlds().iterator().next().getId();
    }

    private CircuitElementPayload waitForElementPayload() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Payload p = inbound.poll(100, TimeUnit.MILLISECONDS);
            if (p instanceof CircuitElementPayload cep) {
                return cep;
            }
            // Allow stray snapshot/lifecycle traffic; ignore.
        }
        return null;
    }

    @Test
    void placeNodePayload_createsNodeWithPositionInfo() throws Exception {
        UUID world = firstWorldId();
        ch.writeAndFlush(new PlaceNodePayload(world, AllComponents.CONNECTION.getTypeId(), 3.0, -2.0)).sync();

        CircuitElementPayload reply = waitForElementPayload();
        assertNotNull(reply, "Expected CircuitElementPayload after PlaceNodePayload");
        assertEquals(1, reply.getChanges().size(), "Single insert expected");
        CircuitElementChange ch0 = reply.getChanges().get(0);
        assertEquals(CircuitElementChange.Kind.INSERT, ch0.kind());
        assertEquals(AllComponents.CONNECTION.getTypeId(), ch0.registryTypeId());

        double[] pos = readPosition(ch0.data());
        assertEquals(3.0, pos[0], 1e-9);
        assertEquals(-2.0, pos[1], 1e-9);
    }

    @Test
    void placeComponentPayload_createsComponentAndPortPositions() throws Exception {
        UUID world = firstWorldId();
        ch.writeAndFlush(new PlaceComponentPayload(world, AllComponents.BJ_TRANSISTOR.getTypeId(),
                10.0, 5.0, Math.PI / 2.0)).sync();

        // Multiple element-payloads arrive: internal nodes + edges + the component itself. Drain until
        // we see a payload whose change list contains the component (matching its registry id).
        boolean sawComponent = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!sawComponent && System.nanoTime() < deadline) {
            CircuitElementPayload reply = waitForElementPayload();
            if (reply == null) break;
            for (CircuitElementChange c : reply.getChanges()) {
                if (c.kind() != CircuitElementChange.Kind.INSERT) continue;
                if (AllComponents.BJ_TRANSISTOR.getTypeId().equals(c.registryTypeId())) {
                    double[] pos = readPosition(c.data());
                    assertEquals(10.0, pos[0], 1e-9);
                    assertEquals(5.0, pos[1], 1e-9);
                    double angle = readAngle(c.data());
                    assertEquals(Math.PI / 2.0, angle, 1e-9);
                    sawComponent = true;
                    break;
                }
            }
        }
        assertTrue(sawComponent, "Expected an INSERT change for the BJ_TRANSISTOR with position + rotation");
    }

    @Test
    void connectEdgePayload_connectsExistingNodes() throws Exception {
        UUID world = firstWorldId();

        // Place two nodes first.
        ch.writeAndFlush(new PlaceNodePayload(world, AllComponents.CONNECTION.getTypeId(), 0.0, 0.0)).sync();
        ch.writeAndFlush(new PlaceNodePayload(world, AllComponents.CONNECTION.getTypeId(), 1.0, 0.0)).sync();

        UUID firstNode = waitForInsertId(AllComponents.CONNECTION.getTypeId());
        UUID secondNode = waitForInsertId(AllComponents.CONNECTION.getTypeId());
        assertNotNull(firstNode, "First placed node should be reported via replication");
        assertNotNull(secondNode, "Second placed node should be reported via replication");

        ch.writeAndFlush(new ConnectEdgePayload(world, AllComponents.RESISTOR.getTypeId(),
                firstNode, secondNode)).sync();

        boolean sawEdge = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!sawEdge && System.nanoTime() < deadline) {
            CircuitElementPayload reply = waitForElementPayload();
            if (reply == null) break;
            for (CircuitElementChange c : reply.getChanges()) {
                if (c.kind() == CircuitElementChange.Kind.INSERT
                        && AllComponents.RESISTOR.getTypeId().equals(c.registryTypeId())) {
                    sawEdge = true;
                    break;
                }
            }
        }
        assertTrue(sawEdge, "Expected an INSERT change for the resistor edge");
    }

    private UUID waitForInsertId(String typeId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            CircuitElementPayload reply = waitForElementPayload();
            if (reply == null) return null;
            for (CircuitElementChange c : reply.getChanges()) {
                if (c.kind() == CircuitElementChange.Kind.INSERT && typeId.equals(c.registryTypeId())) {
                    return TagUtil.getUUID(c.data(), CoreStrings.ELEMENT_ID);
                }
            }
        }
        return null;
    }

    private static double[] readPosition(CompoundTag elementData) {
        Tag t = elementData.get(CoreStrings.INFOS);
        assertTrue(t instanceof CompoundTag, "Element data should carry an 'infos' compound");
        CompoundTag infos = (CompoundTag) t;
        Tag p = infos.get(AllElementInfos.POSITION.getTypeId());
        assertTrue(p instanceof CompoundTag, "infos should contain a position sub-compound");
        CompoundTag pos = (CompoundTag) p;
        return new double[]{pos.getDouble("x"), pos.getDouble("y")};
    }

    private static double readAngle(CompoundTag elementData) {
        Tag t = elementData.get(CoreStrings.INFOS);
        CompoundTag infos = (CompoundTag) t;
        Tag r = infos.get(AllElementInfos.ROTATION.getTypeId());
        assertTrue(r instanceof CompoundTag, "infos should contain a rotation sub-compound");
        return ((CompoundTag) r).getDouble("angle");
    }
}
