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
import com.minecart.protocol.payload.server.CircuitLifecyclePayload;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

    /**
     * Regression: {@code ServerWorld.createNode} silently allocates a fresh circuit on every node placement,
     * and prior to wiring {@link CircuitLifecyclePayload} broadcasting, the client received the resulting
     * {@link CircuitElementPayload} for a circuit id it had never been told about and crashed with
     * "No circuit for id ... in world ...". This test pins the fix: a CircuitLifecyclePayload(INSERT) for the
     * new circuit must arrive BEFORE the CircuitElementPayload referencing it.
     */
    @Test
    void placeNodePayload_announcesNewCircuitBeforeElementDelta() throws Exception {
        UUID world = firstWorldId();
        ch.writeAndFlush(new PlaceNodePayload(world, AllComponents.CONNECTION.getTypeId(), 0.0, 0.0)).sync();

        CircuitLifecyclePayload lifecycle = null;
        CircuitElementPayload element = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((lifecycle == null || element == null) && System.nanoTime() < deadline) {
            Payload p = inbound.poll(100, TimeUnit.MILLISECONDS);
            if (p == null) continue;
            if (p instanceof CircuitLifecyclePayload clp && clp.getKind() == CircuitLifecyclePayload.Kind.INSERT) {
                if (lifecycle == null) {
                    assertNull(element, "Lifecycle INSERT must arrive BEFORE the element delta — the client "
                            + "needs the circuit to exist before applying any change to it.");
                    lifecycle = clp;
                }
            } else if (p instanceof CircuitElementPayload cep) {
                if (element == null) element = cep;
            }
        }
        assertNotNull(lifecycle, "Expected CircuitLifecyclePayload(INSERT) for the newly created circuit");
        assertNotNull(element, "Expected CircuitElementPayload(INSERT) for the placed node");
        assertEquals(world, lifecycle.getWorldId());
        assertEquals(world, element.getWorldId());
        assertEquals(lifecycle.getCircuitId(), element.getCircuitId(),
                "Lifecycle and element messages must refer to the same circuit id");
    }

    /**
     * Regression: when an edge is dropped between two nodes that live in different circuits, the server's
     * {@code ServerWorld.connectInternal} merges the two circuits via {@code Circuit.mergeInto}, which
     * silently moves nodes/edges from the secondary circuit into the primary. Before this fix, the client
     * received the edge INSERT delta scoped to the destination circuit, but its endpoint nodes still lived
     * in the original (now-empty) circuit on the client mirror — so {@code CircuitEdge.attachEndpointsFromTag}
     * threw "Missing endpoint node for edge ...". This test pins the fix: the destination circuit's payload
     * carries a CHANGE op with {@code sourceCircuitId} for each moved node, and the rebind ops sort BEFORE
     * the edge INSERT.
     */
    @Test
    void connectEdgePayload_emitsRebindBeforeEdgeInsertOnMerge() throws Exception {
        UUID world = firstWorldId();

        // Place two free nodes — each goes into its own freshly-allocated server-side circuit.
        ch.writeAndFlush(new PlaceNodePayload(world, AllComponents.CONNECTION.getTypeId(), 0.0, 0.0)).sync();
        ch.writeAndFlush(new PlaceNodePayload(world, AllComponents.CONNECTION.getTypeId(), 5.0, 0.0)).sync();
        UUID firstNode = waitForInsertId(AllComponents.CONNECTION.getTypeId());
        UUID secondNode = waitForInsertId(AllComponents.CONNECTION.getTypeId());
        assertNotNull(firstNode);
        assertNotNull(secondNode);

        // Connect them. On the server this fuses the two circuits; the destination circuit's payload should
        // carry rebind CHANGE ops (sourceCircuitId set) for the migrated endpoint(s) followed by the edge INSERT.
        ch.writeAndFlush(new ConnectEdgePayload(world, AllComponents.RESISTOR.getTypeId(),
                firstNode, secondNode)).sync();

        boolean sawRebindThenInsert = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        outer:
        while (System.nanoTime() < deadline) {
            Payload p = inbound.poll(100, TimeUnit.MILLISECONDS);
            if (!(p instanceof CircuitElementPayload reply)) continue;
            int rebindIdx = -1;
            int edgeInsertIdx = -1;
            List<CircuitElementChange> changes = reply.getChanges();
            for (int i = 0; i < changes.size(); i++) {
                CircuitElementChange c = changes.get(i);
                if (c.kind() == CircuitElementChange.Kind.CHANGE && c.sourceCircuitId() != null
                        && (secondNode.equals(c.elementId()) || firstNode.equals(c.elementId()))) {
                    if (rebindIdx == -1) rebindIdx = i;
                }
                if (c.kind() == CircuitElementChange.Kind.INSERT
                        && AllComponents.RESISTOR.getTypeId().equals(c.registryTypeId())) {
                    edgeInsertIdx = i;
                }
            }
            if (rebindIdx >= 0 && edgeInsertIdx >= 0) {
                assertTrue(rebindIdx < edgeInsertIdx,
                        "Rebind CHANGE must precede edge INSERT inside the merged circuit's payload "
                                + "(rebind=" + rebindIdx + ", insert=" + edgeInsertIdx + ")");
                sawRebindThenInsert = true;
                break outer;
            }
        }
        assertTrue(sawRebindThenInsert,
                "Expected a CircuitElementPayload containing a rebind CHANGE followed by the edge INSERT");
    }

    /**
     * Regression for the BJT phantom-circuit crash:
     * <p>{@code BJTransistor.generate()} allocates four nodes via {@code ServerWorld.createNodeForComponent},
     * each in its own freshly-minted circuit (C1..C4), then connects them with three edges. Every
     * {@code newEdge} merges two of those circuits and removes the now-empty one, so by tick end only one
     * circuit survives. {@link com.minecart.server.listener.CircuitLifecycleListener} correctly coalesces the
     * insert/remove pair for the transient circuits so the client never hears about C2/C3/C4.
     *
     * <p>Before per-element accumulation in {@link com.minecart.server.listener.CircuitElementListener}, the
     * listener still queued a REBIND CHANGE referencing C2/C3/C4 as {@code sourceCircuitId} (because elements
     * had briefly lived there), and a node INSERT keyed by a transient circuit id. The client received either
     * and threw {@code IllegalArgumentException: REBIND: missing source circuit ...} or
     * {@code No circuit for id ...}. This test pins the fix by checking the wire contract directly:
     * <ul>
     *   <li>No {@link CircuitElementChange#sourceCircuitId()} on any change in any payload — newly inserted
     *       elements have no client-side history to rebind from.</li>
     *   <li>Every {@link CircuitElementPayload#getCircuitId()} was previously announced via a
     *       {@link CircuitLifecyclePayload}(INSERT) or {@link CircuitSnapshotPayload} for the same world.</li>
     * </ul>
     */
    @Test
    void placeComponentPayload_doesNotEmitRebindForTransientCircuits() throws Exception {
        UUID world = firstWorldId();
        ch.writeAndFlush(new PlaceComponentPayload(world, AllComponents.BJ_TRANSISTOR.getTypeId(),
                0.0, 0.0, 0.0)).sync();

        // Drain ~1.5s of server traffic; long enough for the lifecycle + element payloads to arrive but
        // short enough to fail fast if the dispatcher stalls.
        Set<UUID> announcedCircuits = new HashSet<>();
        List<CircuitElementPayload> elementPayloads = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
        while (System.nanoTime() < deadline) {
            Payload p = inbound.poll(100, TimeUnit.MILLISECONDS);
            if (p == null) continue;
            if (p instanceof CircuitLifecyclePayload clp
                    && clp.getKind() == CircuitLifecyclePayload.Kind.INSERT
                    && world.equals(clp.getWorldId())) {
                announcedCircuits.add(clp.getCircuitId());
            } else if (p instanceof CircuitSnapshotPayload csp && world.equals(csp.getWorldId())) {
                // Snapshot embeds the circuit id inside the body tag rather than exposing it as a getter.
                UUID snapCircuitId = TagUtil.getUUID(csp.getCircuitData(), CoreStrings.CIRCUIT_ID);
                if (snapCircuitId != null) {
                    announcedCircuits.add(snapCircuitId);
                }
            } else if (p instanceof CircuitElementPayload cep && world.equals(cep.getWorldId())) {
                elementPayloads.add(cep);
            }
        }

        assertTrue(!elementPayloads.isEmpty(),
                "Expected at least one CircuitElementPayload from the BJT placement");

        for (CircuitElementPayload payload : elementPayloads) {
            // Every CircuitElementPayload must reference a circuit the client has been told about.
            if (!announcedCircuits.contains(payload.getCircuitId())) {
                fail("CircuitElementPayload references phantom circuit " + payload.getCircuitId()
                        + " that was never announced via CircuitLifecyclePayload(INSERT) or "
                        + "CircuitSnapshotPayload. Announced: " + announcedCircuits);
            }
            for (CircuitElementChange c : payload.getChanges()) {
                // Newly-inserted elements (and the transient circuits they passed through) must never
                // surface as a rebind CHANGE — the client wouldn't know the source circuit.
                if (c.sourceCircuitId() != null) {
                    fail("Unexpected rebind CHANGE for element " + c.elementId()
                            + " carrying sourceCircuitId=" + c.sourceCircuitId()
                            + " during component placement; per-element accumulation should drop it.");
                }
            }
        }
    }

    /**
     * Regression: before this fix, {@link com.minecart.server.handler.PlaceComponentHandler} only stamped
     * {@link com.minecart.variant.info.PositionInfo} onto port nodes registered in the
     * {@link com.minecart.registry.ComponentAnchorRegistry}. The BJT also owns an internal {@code center}
     * node that has no anchor, so it stayed parked at the world origin and the renderer drew a spurious
     * connection dot at (0, 0) whenever a transistor was placed away from origin. This test pins the new
     * behaviour: every CONNECTION node insert that arrives during a BJT placement at (cx, cy) carries a
     * position info equal to (cx, cy) for the unanchored centre, or to a rotated anchor offset for a port.
     * Nothing lands at the origin.
     */
    @Test
    void placeComponentPayload_internalNodesAreNotStrandedAtOrigin() throws Exception {
        UUID world = firstWorldId();
        double cx = 10.0;
        double cy = 5.0;
        ch.writeAndFlush(new PlaceComponentPayload(world, AllComponents.BJ_TRANSISTOR.getTypeId(),
                cx, cy, 0.0)).sync();

        // BJTransistor.generate() creates 4 internal CONNECTION nodes. Drain element payloads until we've
        // seen at least four CONNECTION inserts (some may be spread across multiple payloads).
        int seen = 0;
        boolean centreAtComponentCentre = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (seen < 4 && System.nanoTime() < deadline) {
            CircuitElementPayload reply = waitForElementPayload();
            if (reply == null) break;
            for (CircuitElementChange c : reply.getChanges()) {
                if (c.kind() != CircuitElementChange.Kind.INSERT) continue;
                if (!AllComponents.CONNECTION.getTypeId().equals(c.registryTypeId())) continue;
                double[] pos = readPosition(c.data());
                seen++;
                assertTrue(pos[0] != 0.0 || pos[1] != 0.0,
                        "Internal BJT node should not be left at the world origin: "
                                + "x=" + pos[0] + " y=" + pos[1]);
                if (Math.abs(pos[0] - cx) < 1e-9 && Math.abs(pos[1] - cy) < 1e-9) {
                    centreAtComponentCentre = true;
                }
            }
        }
        assertTrue(seen >= 4, "Expected 4 internal CONNECTION nodes for BJ_TRANSISTOR, saw " + seen);
        assertTrue(centreAtComponentCentre,
                "Expected the BJT centre node to be parked at the component centre (" + cx + ", " + cy + ")");
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
