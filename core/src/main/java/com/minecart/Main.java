package com.minecart;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.minecart.logic.Circuit;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerCircuit;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.registry.AllComponents;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;
import com.minecart.serialization.tag.DoubleTag;
import com.minecart.serialization.tag.ListTag;
import com.minecart.serialization.tag.Tag;
import org.ejml.interfaces.linsol.LinearSolverSparse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class Main {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static int failureCount;

    public static void main(String[] args) {
        try {
            testCircuitLongResistorChain();
//            testCircuitStarTopology();
//            testCircuitMixedResistorAndBatteryEdges();
//            testCircuitTagBinaryRoundTripMatchesJson();
//            testCircuitSaveLoadIdempotentJson();
        } catch (IOException e) {
            return;
        }
    }

    /**
     * Linear graph: many segments, distinct element ids, electrical scalars round-trip on edges.
     */
    private static void testCircuitLongResistorChain() throws IOException {
        System.out.println("--- Long resistor chain (5 nodes, 4 resistors) ---\n");
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();

        CircuitNode[] nodes = new CircuitNode[5];
        nodes[0] = w.createNode(AllComponents.CONNECTION);
        for (int i = 1; i < 5; i++) {
            nodes[i] = w.createNode(AllComponents.CONNECTION);
            CircuitEdge e = w.connect(AllComponents.RESISTOR, nodes[i - 1], nodes[i]);
            Objects.requireNonNull(e);
            e.getCurrent().setValue(0.25 * i);
        }

        Circuit original = nodes[0].getCircuit();

        CompoundTag tag = new CompoundTag();
        original.save(tag);

        ServerWorld w2 = level.createWorld();
        ServerCircuit loaded = ServerCircuit.loadFromTag(w2, tag);

        String result = GSON.toJson(tag.writeJson());
        System.out.println(result);

        CompoundTag tageous = new CompoundTag();
        tageous.readJson(JsonParser.parseString(result));
        UUID rid = TagUtil.getUUID(tageous, "circuit_id");
        ServerCircuit retrieve = new ServerCircuit(Objects.requireNonNull(rid));
        retrieve.setWorld(w);
        retrieve.load(w, tageous);
        System.out.println(1);
        System.out.println(2);
    }

    /**
     * One hub node with several leaves (branching), multiple resistors.
     */
    private static void testCircuitStarTopology() throws IOException {
        System.out.println("--- Star topology (1 hub + 4 leaves) ---\n");
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();

        CircuitNode hub = w.createNode(AllComponents.CONNECTION);
        List<CircuitNode> leaves = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            CircuitNode leaf = w.createNode(AllComponents.CONNECTION);
            leaves.add(leaf);
            CircuitEdge e = w.connect(AllComponents.RESISTOR, hub, leaf);
            Objects.requireNonNull(e);
            e.getCurrent().setValue(-0.1 * (i + 1));
        }

        Circuit original = hub.getCircuit();
        CompoundTag tag = new CompoundTag();
        original.save(tag);

        ServerWorld w2 = level.createWorld();
        ServerCircuit loaded = ServerCircuit.loadFromTag(w2, tag);

        assertCircuitDeepEquals("star", original, loaded);
    }

    /**
     * Different edge registry types on the same circuit (resistor + battery).
     */
    private static void testCircuitMixedResistorAndBatteryEdges() throws IOException {
        System.out.println("--- Mixed edge types (resistor + battery) ---\n");
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();

        CircuitNode a = w.createNode(AllComponents.CONNECTION);
        CircuitNode b = w.createNode(AllComponents.CONNECTION);
        CircuitNode c = w.createNode(AllComponents.CONNECTION);

        CircuitEdge r1 = w.connect(AllComponents.RESISTOR, a, b);
        CircuitEdge bat = w.connect(AllComponents.BATTERY, b, c);
        Objects.requireNonNull(r1);
        Objects.requireNonNull(bat);
        r1.getCurrent().setValue(0.5);
        bat.getCurrent().setValue(-0.5);

        Circuit original = a.getCircuit();
        CompoundTag tag = new CompoundTag();
        original.save(tag);

        ServerWorld w2 = level.createWorld();
        ServerCircuit loaded = ServerCircuit.loadFromTag(w2, tag);

        assertCircuitDeepEquals("mixed-edges", original, loaded);
        assertCondition(
                "mixed: two edge registry ids present after load",
                loaded.edges().stream().map(e -> e.getRegistryTypeId()).filter(Objects::nonNull).distinct().count() >= 2);
    }

    /**
     * Full circuit {@link CompoundTag}: binary write/read preserves JSON identical to direct save.
     */
    private static void testCircuitTagBinaryRoundTripMatchesJson() throws IOException {
        System.out.println("--- Circuit tag: binary round-trip vs JSON ---\n");
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();
        CircuitNode n1 = w.createNode(AllComponents.CONNECTION);
        CircuitNode n2 = w.createNode(AllComponents.CONNECTION);
        CircuitEdge e = w.connect(AllComponents.RESISTOR, n1, n2);
        Objects.requireNonNull(e);
        e.getCurrent().setValue(1.414);

        Circuit circuit = n1.getCircuit();
        CompoundTag saved = new CompoundTag();
        circuit.save(saved);

        byte[] bytes;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(buffer)) {
            Tag.writeBinary(out, saved);
            bytes = buffer.toByteArray();
        }

        Tag.BinaryWithContext decoded;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            decoded = Tag.readBinary(in);
        }

        CompoundTag fromBinary = (CompoundTag) decoded.root();
        String jsonDirect = GSON.toJson(saved.writeJson());
        String jsonFromBin = GSON.toJson(fromBinary.writeJson());

        assertCondition("circuit tag: binary preserves JSON", jsonDirect.equals(jsonFromBin));
        System.out.println("Binary payload size (circuit tag): " + bytes.length + " bytes\n");
    }

    /**
     * save → load → save JSON equals first save JSON (stable round-trip).
     */
    private static void testCircuitSaveLoadIdempotentJson() throws IOException {
        System.out.println("--- Circuit: save → load → save (JSON idempotent) ---\n");
        ServerLevel level = new ServerLevel();
        ServerWorld w = level.createWorld();

        CircuitNode x = w.createNode(AllComponents.CONNECTION);
        CircuitNode y = w.createNode(AllComponents.CONNECTION);
        CircuitNode z = w.createNode(AllComponents.CONNECTION);
        w.connect(AllComponents.RESISTOR, x, y);
        w.connect(AllComponents.RESISTOR, y, z);

        Circuit first = x.getCircuit();
        CompoundTag t1 = new CompoundTag();
        first.save(t1);
        String json1 = GSON.toJson(t1.writeJson());

        ServerWorld w2 = level.createWorld();
        ServerCircuit second = ServerCircuit.loadFromTag(w2, t1);
        CompoundTag t2 = new CompoundTag();
        second.save(t2);
        String json2 = GSON.toJson(t2.writeJson());

        assertCondition("second save JSON equals first save JSON", json1.equals(json2));
    }

    private static void assertCircuitDeepEquals(String label, Circuit original, Circuit loaded) {
        assertCondition(label + ": circuit id", original.getId().equals(loaded.getId()));
        assertCondition(label + ": node count", original.nodes().size() == loaded.nodes().size());
        assertCondition(label + ": edge count", original.edges().size() == loaded.edges().size());
        assertCondition(label + ": component count", original.components().size() == loaded.components().size());

        Set<UUID> origNodeIds = new HashSet<>();
        for (CircuitNode n : original.nodes()) {
            origNodeIds.add(n.getId());
        }
        Set<UUID> loadNodeIds = new HashSet<>();
        for (CircuitNode n : loaded.nodes()) {
            loadNodeIds.add(n.getId());
        }
        assertCondition(label + ": node id set", origNodeIds.equals(loadNodeIds));

        Set<UUID> origEdgeIds = new HashSet<>();
        for (CircuitEdge e : original.edges()) {
            origEdgeIds.add(e.getId());
        }
        Set<UUID> loadEdgeIds = new HashSet<>();
        for (CircuitEdge e : loaded.edges()) {
            loadEdgeIds.add(e.getId());
        }
        assertCondition(label + ": edge id set", origEdgeIds.equals(loadEdgeIds));

        for (CircuitNode n : original.nodes()) {
            CircuitNode n2 = loaded.findNode(n.getId());
            assertCondition(label + ": node " + n.getId() + " exists", n2 != null);
            if (n2 == null) {
                continue;
            }
            assertCondition(label + ": node ground " + n.getId(), n.isGrounded() == n2.isGrounded());
            assertCondition(label + ": node voltage " + n.getId(), n.getVoltage().getValue() == n2.getVoltage().getValue());
            String rt = n.getRegistryTypeId();
            assertCondition(label + ": node type " + n.getId(), Objects.equals(rt, n2.getRegistryTypeId()));
        }

        for (CircuitEdge e : original.edges()) {
            CircuitEdge e2 = loaded.findEdge(e.getId());
            assertCondition(label + ": edge " + e.getId() + " exists", e2 != null);
            if (e2 == null) {
                continue;
            }
            assertCondition(
                    label + ": edge " + e.getId() + " start",
                    e.getStart().getId().equals(e2.getStart().getId()));
            assertCondition(
                    label + ": edge " + e.getId() + " end",
                    e.getEnd().getId().equals(e2.getEnd().getId()));
            assertCondition(label + ": edge current " + e.getId(), e.getCurrent().getValue() == e2.getCurrent().getValue());
            assertCondition(label + ": edge overpowered " + e.getId(), e.isOverpowered() == e2.isOverpowered());
            assertCondition(label + ": edge type " + e.getId(), Objects.equals(e.getRegistryTypeId(), e2.getRegistryTypeId()));
        }
    }

    private static void testJsonRoundTrip(CompoundTag original, UUID expectedUuid) throws IOException {
        System.out.println("--- JSON round-trip ---\n");

        JsonElement jsonOutput = original.writeJson();
        System.out.println(GSON.toJson(jsonOutput));

        CompoundTag loaded = new CompoundTag();
        loaded.readJson(jsonOutput);

        assertCondition("JSON: UUID preserved", expectedUuid.equals(TagUtil.getUUID(loaded, "uuid")));
        assertCondition("JSON: id string", "minecart:battery".equals(loaded.getString("id")));
        assertCondition("JSON: tick_delay", loaded.getInt("tick_delay") == 20);
    }

    private static void testBinaryTagRoundTrip(CompoundTag original, UUID expectedUuid) throws IOException {
        System.out.println("--- Binary round-trip (SerializationContext + Tag) ---\n");

        byte[] bytes;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(buffer)) {
            Tag.writeBinary(out, original);
            bytes = buffer.toByteArray();
        }

        System.out.println("Binary size: " + bytes.length + " bytes");

        Tag.BinaryWithContext decoded;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            decoded = Tag.readBinary(in);
        }

        CompoundTag roundTrip = (CompoundTag) decoded.root();

        String jsonBefore = GSON.toJson(original.writeJson());
        String jsonAfter = GSON.toJson(roundTrip.writeJson());

        assertCondition("Binary: JSON matches", jsonBefore.equals(jsonAfter));
        assertCondition("Binary: context has bindings", decoded.context().size() > 0);
        assertCondition("Binary: UUID preserved", expectedUuid.equals(TagUtil.getUUID(roundTrip, "uuid")));
        assertCondition("Binary: nested list size",
                roundTrip.get("tick_history") instanceof ListTag list && list.size() == 3);
    }

    private static void assertCondition(String testName, boolean condition) {
        if (condition) {
            System.out.println("[PASS] " + testName);
        } else {
            System.err.println("[FAIL] " + testName);
            failureCount++;
        }
    }
}
