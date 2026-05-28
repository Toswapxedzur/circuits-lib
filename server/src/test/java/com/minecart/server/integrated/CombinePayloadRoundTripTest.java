package com.minecart.server.integrated;

import com.minecart.misc.CoreStrings;
import com.minecart.protocol.codec.PayloadDecoder;
import com.minecart.protocol.codec.PayloadEncoder;
import com.minecart.protocol.payload.Payload;
import com.minecart.protocol.payload.client.CombineCascadePayload;
import com.minecart.protocol.payload.client.ConnectEdgePayload;
import com.minecart.protocol.payload.client.DeleteElementPayload;
import com.minecart.protocol.payload.client.EdgeEndpointChangePayload;
import com.minecart.protocol.payload.client.PlaceNodePayload;
import com.minecart.protocol.payload.client.ReplaceComponentNodePayload;
import com.minecart.protocol.payload.server.CircuitElementChange;
import com.minecart.protocol.payload.server.CircuitElementPayload;
import com.minecart.protocol.payload.server.CircuitLifecyclePayload;
import com.minecart.protocol.payload.server.CircuitSnapshotPayload;
import com.minecart.protocol.payload.server.WorldLifecyclePayload;
import com.minecart.registry.AllComponents;
import com.minecart.serialization.TagUtil;

import java.util.HashSet;
import java.util.Set;
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
 * End-to-end coverage for the granular node-combine wire protocol: an
 * {@link EdgeEndpointChangePayload} should land as authoritative endpoint mutation on the server and
 * be replicated as a CHANGE op carrying the new endpoint UUIDs back to the client.
 *
 * <p>Drives the full Netty {@link io.netty.channel.local.LocalChannel} pipeline (decoder/encoder +
 * dispatcher + tick thread + replication broadcast) so the test catches breakages at any layer.
 */
class CombinePayloadRoundTripTest {

    private IntegratedServer server;
    private EventLoopGroup loop;
    private Channel ch;
    private BlockingQueue<Payload> inbound;

    @BeforeEach
    void setUp() throws Exception {
        server = new IntegratedServer();
        server.start();
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
        }
        return null;
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

    /**
     * Drives the full granular sequence the editor's drag-to-combine flow generates:
     *
     * <ol>
     *     <li>Place three free nodes A, B, C.</li>
     *     <li>Connect B and C with a wire.</li>
     *     <li>Send {@link EdgeEndpointChangePayload} swapping the wire's start from B to A.</li>
     *     <li>Send {@link DeleteElementPayload} for B.</li>
     * </ol>
     *
     * <p>Pin-points: the resulting CHANGE op for the wire must carry the new {@code start} UUID in its
     * serialized data (so the client mirror's {@code CircuitEdge.load} resync branch can detach from B
     * and reattach to A), and the subsequent REMOVE op must arrive without errors.
     */
    @Test
    void edgeEndpointChangePayload_mutatesEdgeEndpointAndReplicatesIt() throws Exception {
        UUID world = firstWorldId();

        ch.writeAndFlush(new PlaceNodePayload(world, AllComponents.CONNECTION.getTypeId(), 0.0, 0.0)).sync();
        ch.writeAndFlush(new PlaceNodePayload(world, AllComponents.CONNECTION.getTypeId(), 1.0, 0.0)).sync();
        ch.writeAndFlush(new PlaceNodePayload(world, AllComponents.CONNECTION.getTypeId(), 2.0, 0.0)).sync();
        UUID nodeA = waitForInsertId(AllComponents.CONNECTION.getTypeId());
        UUID nodeB = waitForInsertId(AllComponents.CONNECTION.getTypeId());
        UUID nodeC = waitForInsertId(AllComponents.CONNECTION.getTypeId());
        assertNotNull(nodeA);
        assertNotNull(nodeB);
        assertNotNull(nodeC);

        ch.writeAndFlush(new ConnectEdgePayload(world, AllComponents.RESISTOR.getTypeId(), nodeB, nodeC)).sync();
        UUID wireId = waitForInsertId(AllComponents.RESISTOR.getTypeId());
        assertNotNull(wireId);

        // Repoint the wire from B–C to A–C; B should lose its only edge.
        ch.writeAndFlush(new EdgeEndpointChangePayload(world, wireId, nodeA, nodeC)).sync();

        boolean sawEndpointChange = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!sawEndpointChange && System.nanoTime() < deadline) {
            CircuitElementPayload reply = waitForElementPayload();
            if (reply == null) break;
            for (CircuitElementChange c : reply.getChanges()) {
                if (c.kind() != CircuitElementChange.Kind.CHANGE) continue;
                if (!wireId.equals(c.elementId())) continue;
                if (c.data() == null) continue;
                UUID newStart = TagUtil.getUUID(c.data(), CoreStrings.EDGE_START);
                UUID newEnd = TagUtil.getUUID(c.data(), CoreStrings.EDGE_END);
                if (nodeA.equals(newStart) && nodeC.equals(newEnd)) {
                    sawEndpointChange = true;
                    break;
                }
            }
        }
        assertTrue(sawEndpointChange,
                "Expected a CHANGE op carrying the new (A, C) endpoints for the wire after the "
                        + "EdgeEndpointChangePayload");

        // Authoritative server state should match: wire connects A and C; B is isolated.
        CountDownLatch verified = new CountDownLatch(1);
        server.level().submit(() -> {
            var w = server.level().findWorld(world);
            assertNotNull(w);
            var wire = w.findEdge(wireId);
            assertNotNull(wire);
            assertEquals(nodeA, wire.getStart() != null ? wire.getStart().getId() : null);
            assertEquals(nodeC, wire.getEnd() != null ? wire.getEnd().getId() : null);
            verified.countDown();
        });
        assertTrue(verified.await(2, TimeUnit.SECONDS), "Server-side assertion didn't run within 2s");
    }

    /**
     * Sanity check that a payload with self-loop endpoints is silently dropped (server log unchanged,
     * no CHANGE op for the edge surfaces). Mirrors the {@link com.minecart.logic.ServerWorld#changeEdgeEndpoint}
     * guard which throws on self-loops; the handler catches the exception and emits nothing.
     */
    @Test
    void edgeEndpointChangePayload_selfLoopIsRejected() throws Exception {
        UUID world = firstWorldId();
        ch.writeAndFlush(new PlaceNodePayload(world, AllComponents.CONNECTION.getTypeId(), 0.0, 0.0)).sync();
        ch.writeAndFlush(new PlaceNodePayload(world, AllComponents.CONNECTION.getTypeId(), 1.0, 0.0)).sync();
        UUID nodeA = waitForInsertId(AllComponents.CONNECTION.getTypeId());
        UUID nodeB = waitForInsertId(AllComponents.CONNECTION.getTypeId());
        assertNotNull(nodeA);
        assertNotNull(nodeB);
        ch.writeAndFlush(new ConnectEdgePayload(world, AllComponents.RESISTOR.getTypeId(), nodeA, nodeB)).sync();
        UUID wireId = waitForInsertId(AllComponents.RESISTOR.getTypeId());
        assertNotNull(wireId);

        // Both ends pointing at A — self-loop, should be silently dropped server-side.
        ch.writeAndFlush(new EdgeEndpointChangePayload(world, wireId, nodeA, nodeA)).sync();

        // Drain ~1s of traffic; we should NOT see a CHANGE that re-points both ends at nodeA.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1000);
        while (System.nanoTime() < deadline) {
            Payload p = inbound.poll(100, TimeUnit.MILLISECONDS);
            if (!(p instanceof CircuitElementPayload reply)) continue;
            for (CircuitElementChange c : reply.getChanges()) {
                if (c.kind() == CircuitElementChange.Kind.CHANGE
                        && wireId.equals(c.elementId())
                        && c.data() != null) {
                    UUID newStart = TagUtil.getUUID(c.data(), CoreStrings.EDGE_START);
                    UUID newEnd = TagUtil.getUUID(c.data(), CoreStrings.EDGE_END);
                    assertTrue(!(nodeA.equals(newStart) && nodeA.equals(newEnd)),
                            "Server should refuse to apply a self-loop endpoint change");
                }
            }
        }
    }

    /**
     * Full combine sequence end-to-end: {@code EdgeEndpointChange} + {@code DeleteElement} as the editor
     * sends them when the user drops a free node onto another free node. Regression for the
     * "{@code No circuit for id ... in world ...}" crash that happened when the destination-key router
     * pointed a REMOVE op at a transient circuit the lifecycle listener had cancelled.
     *
     * <p>Scenario: A, B, C are free nodes with wire B–C. Combining A (survivor) with B (absorbed)
     * sends an {@code EdgeEndpointChange} repointing the wire from B–C to A–C — which on the server
     * isolates B into a fresh {@link com.minecart.logic.ServerCircuit} via
     * {@link com.minecart.logic.ServerWorld#splitOffIfDisconnected}. The subsequent
     * {@code DeleteElement(B)} immediately empties that transient circuit and triggers its removal.
     * {@link com.minecart.server.listener.CircuitLifecycleListener} coalesces the insert/remove pair to
     * a no-op, so the client never hears about the transient circuit. The REMOVE op for B therefore
     * must NOT be keyed to that ghost circuit — the destination-key path routes it to B's original
     * circuit (one the client has already been told about via the lifecycle insert / initial snapshot).
     *
     * <p>The test asserts: every {@code CircuitElementPayload} arriving at the client carries a
     * circuit id the client has been told about, either via {@link CircuitLifecyclePayload} INSERT or
     * via the initial {@link CircuitSnapshotPayload} catch-up.
     */
    @Test
    void combineSequence_routesRemoveOpsToCircuitsClientKnowsAbout() throws Exception {
        UUID world = firstWorldId();
        Set<UUID> knownCircuits = new HashSet<>();

        ch.writeAndFlush(new PlaceNodePayload(world, AllComponents.CONNECTION.getTypeId(), 0.0, 0.0)).sync();
        ch.writeAndFlush(new PlaceNodePayload(world, AllComponents.CONNECTION.getTypeId(), 1.0, 0.0)).sync();
        ch.writeAndFlush(new PlaceNodePayload(world, AllComponents.CONNECTION.getTypeId(), 2.0, 0.0)).sync();
        UUID nodeA = drainUntilInsert(AllComponents.CONNECTION.getTypeId(), knownCircuits);
        UUID nodeB = drainUntilInsert(AllComponents.CONNECTION.getTypeId(), knownCircuits);
        UUID nodeC = drainUntilInsert(AllComponents.CONNECTION.getTypeId(), knownCircuits);
        assertNotNull(nodeA);
        assertNotNull(nodeB);
        assertNotNull(nodeC);
        ch.writeAndFlush(new ConnectEdgePayload(world, AllComponents.RESISTOR.getTypeId(), nodeB, nodeC)).sync();
        UUID wireId = drainUntilInsert(AllComponents.RESISTOR.getTypeId(), knownCircuits);
        assertNotNull(wireId);

        // Granular combine sequence: repoint wire B–C to A–C, then delete the now-isolated B.
        ch.writeAndFlush(new EdgeEndpointChangePayload(world, wireId, nodeA, nodeC)).sync();
        ch.writeAndFlush(new DeleteElementPayload(world, nodeB)).sync();

        // Drain ~2s of traffic and assert routing invariants.
        boolean sawRemoveOfB = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Payload p = inbound.poll(100, TimeUnit.MILLISECONDS);
            if (p == null) {
                if (sawRemoveOfB) break;
                continue;
            }
            if (p instanceof CircuitLifecyclePayload clp) {
                if (clp.getKind() == CircuitLifecyclePayload.Kind.INSERT) {
                    knownCircuits.add(clp.getCircuitId());
                }
                continue;
            }
            if (p instanceof CircuitSnapshotPayload csp) {
                UUID cid = TagUtil.getUUID(csp.getCircuitData(), CoreStrings.CIRCUIT_ID);
                if (cid != null) knownCircuits.add(cid);
                continue;
            }
            if (p instanceof CircuitElementPayload cep) {
                assertTrue(knownCircuits.contains(cep.getCircuitId()),
                        "CircuitElementPayload routed to circuit " + cep.getCircuitId()
                                + " that the client was never told about. Known circuits: " + knownCircuits);
                for (CircuitElementChange c : cep.getChanges()) {
                    if (c.kind() == CircuitElementChange.Kind.REMOVE && nodeB.equals(c.elementId())) {
                        sawRemoveOfB = true;
                    }
                }
            }
        }
        assertTrue(sawRemoveOfB, "Expected a REMOVE op for the absorbed node B to reach the client");
    }

    /**
     * Drains payloads off {@link #inbound}, recording any circuit ids announced via lifecycle INSERT /
     * snapshot in {@code knownCircuits}, until an INSERT op for {@code typeId} arrives — returns that
     * element's id. Asserts the same routing invariant on every {@link CircuitElementPayload} seen
     * along the way: an element payload may only reference a circuit the client has already heard
     * about via lifecycle INSERT or initial snapshot.
     */
    private UUID drainUntilInsert(String typeId, Set<UUID> knownCircuits) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Payload p = inbound.poll(100, TimeUnit.MILLISECONDS);
            if (p == null) continue;
            if (p instanceof CircuitLifecyclePayload clp
                    && clp.getKind() == CircuitLifecyclePayload.Kind.INSERT) {
                knownCircuits.add(clp.getCircuitId());
                continue;
            }
            if (p instanceof CircuitSnapshotPayload csp) {
                UUID cid = TagUtil.getUUID(csp.getCircuitData(), CoreStrings.CIRCUIT_ID);
                if (cid != null) knownCircuits.add(cid);
                continue;
            }
            if (p instanceof CircuitElementPayload cep) {
                assertTrue(knownCircuits.contains(cep.getCircuitId()),
                        "CircuitElementPayload routed to circuit " + cep.getCircuitId()
                                + " that the client was never told about. Known circuits: " + knownCircuits);
                for (CircuitElementChange c : cep.getChanges()) {
                    if (c.kind() == CircuitElementChange.Kind.INSERT && typeId.equals(c.registryTypeId())) {
                        return TagUtil.getUUID(c.data(), CoreStrings.ELEMENT_ID);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Round-trip smoke for {@link CombineCascadePayload}: send a single-pair cascade on two free
     * nodes wired together, observe REMOVE for the absorbed node and authoritative state ending
     * up with the wire repointed onto the survivor. Same combine semantics as
     * {@code combineSequence_routesRemoveOpsToCircuitsClientKnowsAbout} but driven through the new
     * unified payload instead of the granular trio — verifies that the protocol id is registered,
     * the handler runs, and the cascade engine's {@code DelegateCombineOp} path successfully
     * fires {@link com.minecart.logic.ServerWorld#combineNodes} from inside the server tick.
     */
    @Test
    void combineCascadePayload_freeOnFree_endToEnd() throws Exception {
        UUID world = firstWorldId();
        Set<UUID> knownCircuits = new HashSet<>();

        ch.writeAndFlush(new PlaceNodePayload(world, AllComponents.CONNECTION.getTypeId(), 0.0, 0.0)).sync();
        ch.writeAndFlush(new PlaceNodePayload(world, AllComponents.CONNECTION.getTypeId(), 1.0, 0.0)).sync();
        ch.writeAndFlush(new PlaceNodePayload(world, AllComponents.CONNECTION.getTypeId(), 2.0, 0.0)).sync();
        UUID nodeA = drainUntilInsert(AllComponents.CONNECTION.getTypeId(), knownCircuits);
        UUID nodeB = drainUntilInsert(AllComponents.CONNECTION.getTypeId(), knownCircuits);
        UUID nodeC = drainUntilInsert(AllComponents.CONNECTION.getTypeId(), knownCircuits);
        assertNotNull(nodeA);
        assertNotNull(nodeB);
        assertNotNull(nodeC);
        ch.writeAndFlush(new ConnectEdgePayload(world, AllComponents.RESISTOR.getTypeId(), nodeB, nodeC)).sync();
        UUID wireId = drainUntilInsert(AllComponents.RESISTOR.getTypeId(), knownCircuits);
        assertNotNull(wireId);

        // One-shot cascade: combine B into A.
        ch.writeAndFlush(new CombineCascadePayload(
                world,
                null, // no drag gesture id for this scripted scenario
                java.util.List.of(new CombineCascadePayload.CombinePair(nodeA, nodeB)))).sync();

        boolean sawRemoveOfB = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Payload p = inbound.poll(100, TimeUnit.MILLISECONDS);
            if (p == null) {
                if (sawRemoveOfB) break;
                continue;
            }
            if (p instanceof CircuitLifecyclePayload clp) {
                if (clp.getKind() == CircuitLifecyclePayload.Kind.INSERT) {
                    knownCircuits.add(clp.getCircuitId());
                }
                continue;
            }
            if (p instanceof CircuitSnapshotPayload csp) {
                UUID cid = TagUtil.getUUID(csp.getCircuitData(), CoreStrings.CIRCUIT_ID);
                if (cid != null) knownCircuits.add(cid);
                continue;
            }
            if (p instanceof CircuitElementPayload cep) {
                assertTrue(knownCircuits.contains(cep.getCircuitId()),
                        "CombineCascadePayload reply routed to unknown circuit " + cep.getCircuitId());
                for (CircuitElementChange c : cep.getChanges()) {
                    if (c.kind() == CircuitElementChange.Kind.REMOVE && nodeB.equals(c.elementId())) {
                        sawRemoveOfB = true;
                    }
                }
            }
        }
        assertTrue(sawRemoveOfB, "Expected REMOVE for the absorbed node B after CombineCascadePayload");

        // Authoritative state: wire connects A and C; B no longer exists.
        CountDownLatch verified = new CountDownLatch(1);
        server.level().submit(() -> {
            var w = server.level().findWorld(world);
            assertNotNull(w);
            var wire = w.findEdge(wireId);
            assertNotNull(wire);
            UUID s = wire.getStart() != null ? wire.getStart().getId() : null;
            UUID e = wire.getEnd() != null ? wire.getEnd().getId() : null;
            assertTrue((nodeA.equals(s) && nodeC.equals(e)) || (nodeC.equals(s) && nodeA.equals(e)),
                    "Wire should land on A–C (either direction) after cascade");
            verified.countDown();
        });
        assertTrue(verified.await(2, TimeUnit.SECONDS), "Server-side assertion didn't run within 2s");
    }

    /**
     * {@link ReplaceComponentNodePayload} requires server-side knowledge of an actual port slot, so
     * for now we just smoke-test that sending one with bogus ids is forgivingly ignored: no exception,
     * no spurious deltas — the server simply doesn't update its state and the client sees nothing.
     * Real port-replacement coverage lives in {@code core/CombineNodesTest} where the in-process
     * world API exposes the BJT internals directly without needing a placement protocol round-trip.
     */
    @Test
    void replaceComponentNodePayload_unknownIdsAreIgnored() throws Exception {
        UUID world = firstWorldId();
        UUID bogusComponent = UUID.randomUUID();
        UUID bogusOld = UUID.randomUUID();
        UUID bogusNew = UUID.randomUUID();

        ch.writeAndFlush(new ReplaceComponentNodePayload(world, bogusComponent, bogusOld, bogusNew)).sync();

        // Drain ~500ms of traffic; should not include any element/lifecycle op for the bogus ids.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        while (System.nanoTime() < deadline) {
            Payload p = inbound.poll(100, TimeUnit.MILLISECONDS);
            if (p == null) continue;
            if (p instanceof CircuitElementPayload cep) {
                for (CircuitElementChange c : cep.getChanges()) {
                    assertTrue(!bogusOld.equals(c.elementId()) && !bogusNew.equals(c.elementId()),
                            "Server should not emit deltas for a bogus replace-port request");
                }
            }
            if (p instanceof CircuitLifecyclePayload clp) {
                assertTrue(!bogusComponent.equals(clp.getCircuitId()),
                        "Server should not emit lifecycle ops for a bogus replace-port request");
            }
        }
    }
}
