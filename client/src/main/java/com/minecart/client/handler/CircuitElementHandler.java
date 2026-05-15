package com.minecart.client.handler;

import com.minecart.client.logic.ClientCircuit;
import com.minecart.client.logic.ClientLevel;
import com.minecart.client.logic.ClientWorld;
import com.minecart.foundation.Circuit;
import com.minecart.foundation.Level;
import com.minecart.foundation.World;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitEdge;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.misc.CoreStrings;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.server.CircuitElementChange;
import com.minecart.protocol.payload.server.CircuitElementPayload;
import com.minecart.protocol.sync.SyncRegistry;
import com.minecart.serialization.TagUtil;
import com.minecart.serialization.tag.CompoundTag;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Applies {@link CircuitElementPayload} on a {@link ClientLevel} (server → client replication).
 */
public final class CircuitElementHandler implements PayloadHandler<CircuitElementPayload> {

    private final ClientLevel level;

    public CircuitElementHandler(ClientLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    public ClientLevel getLevel() {
        return level;
    }

    @Override
    public void handle(CircuitElementPayload payload) {
        ResolvedWorldCircuit resolved = resolveWorldAndCircuit(level, payload.getWorldId(), payload.getCircuitId());
        applyDelta(resolved.world(), resolved.circuit(), payload.getChanges());
    }

    /**
     * Applies ordered steps when world and circuit are already resolved.
     */
    public static void handle(CircuitElementPayload payload, ClientWorld world, ClientCircuit circuit) {
        if (!payload.getCircuitId().equals(circuit.getId())) {
            throw new IllegalArgumentException(
                    "Circuit id mismatch: payload " + payload.getCircuitId() + ", actual " + circuit.getId());
        }
        if (circuit.getWorld() != world) {
            throw new IllegalArgumentException("Circuit does not belong to the given world");
        }
        applyDelta(world, circuit, payload.getChanges());
    }

    private static ResolvedWorldCircuit resolveWorldAndCircuit(ClientLevel level, UUID worldId, UUID circuitId) {
        Objects.requireNonNull(circuitId, "Missing circuit id");
        if (worldId != null) {
            World w = level.findWorld(worldId);
            if (w == null) {
                throw new IllegalArgumentException("No world for id: " + worldId);
            }
            if (!(w instanceof ClientWorld cw)) {
                throw new IllegalStateException("World is not a ClientWorld");
            }
            Circuit c = cw.findCircuit(circuitId);
            if (c == null) {
                throw new IllegalArgumentException("No circuit for id: " + circuitId + " in world " + worldId);
            }
            if (!(c instanceof ClientCircuit cc)) {
                throw new IllegalStateException("Circuit is not a ClientCircuit");
            }
            return new ResolvedWorldCircuit(cw, cc);
        }
        Circuit c = level.findCircuit(circuitId);
        if (c == null) {
            throw new IllegalArgumentException("No circuit for id: " + circuitId + " in level");
        }
        if (!(c instanceof ClientCircuit cc)) {
            throw new IllegalStateException("Circuit is not a ClientCircuit");
        }
        World w = findWorldContaining(c, level);
        if (w == null) {
            throw new IllegalArgumentException("Circuit " + circuitId + " is not attached to any world on this level");
        }
        if (!(w instanceof ClientWorld cw)) {
            throw new IllegalStateException("World is not a ClientWorld");
        }
        return new ResolvedWorldCircuit(cw, cc);
    }

    private static World findWorldContaining(Circuit circuit, Level level) {
        for (World w : level.getWorlds()) {
            if (w.getCircuits().contains(circuit)) {
                return w;
            }
        }
        return null;
    }

    private record ResolvedWorldCircuit(ClientWorld world, ClientCircuit circuit) {
    }

    private static void applyDelta(ClientWorld world, ClientCircuit circuit, List<CircuitElementChange> ops) {
        if (circuit.getWorld() != world) {
            throw new IllegalArgumentException("Circuit does not belong to the given world");
        }
        if (ops.isEmpty()) {
            return;
        }
        for (CircuitElementChange op : ops) {
            switch (op.kind()) {
                case REMOVE -> removeOne(world, circuit, op.elementId());
                case INSERT -> insertOne(world, circuit, op.registryTypeId(), op.data());
                case CHANGE -> applySync(world, circuit, op);
            }
        }
    }

    private static void insertOne(ClientWorld world, ClientCircuit circuit, String registryTypeId, CompoundTag data) {
        String inData = data.getString(CoreStrings.ELEMENT_TYPE);
        if (inData != null && !inData.isEmpty() && !registryTypeId.equals(inData)) {
            throw new IllegalArgumentException(
                    "Registry type id mismatch: step " + registryTypeId + ", data " + inData);
        }
        CompoundTag delta = new CompoundTag();
        TagUtil.putUUID(delta, CoreStrings.CIRCUIT_ID, circuit.getId());
        CircuitElement el = CircuitElement.deserialize(data, world);
        if (el instanceof CircuitNode) {
            TagUtil.putCompoundList(delta, CoreStrings.NODES, List.of(data));
        } else if (el instanceof CircuitEdge) {
            TagUtil.putCompoundList(delta, CoreStrings.EDGES, List.of(data));
        } else if (el instanceof CircuitComponent) {
            TagUtil.putCompoundList(delta, CoreStrings.COMPONENTS, List.of(data));
        } else {
            throw new IllegalArgumentException("Unknown element class: " + el.getClass().getName());
        }
        circuit.load(world, delta);
    }

    private static void applySync(ClientWorld world, ClientCircuit destCircuit, CircuitElementChange op) {
        CircuitElement el;
        if (op.sourceCircuitId() != null) {
            // Rebind: move element out of its previous circuit on the client before applying sync data.
            // Mirrors Circuit.mergeInto / Circuit.seperate on the server, which silently rebind element
            // ownership without firing insert/remove events.
            el = rebindElement(world, destCircuit, op.elementId(), op.sourceCircuitId());
        } else {
            el = destCircuit.findElement(op.elementId());
            if (el == null) {
                throw new IllegalArgumentException("CHANGE target not found: " + op.elementId());
            }
        }
        String expected = op.registryTypeId();
        String actual = CircuitElementChange.registryTypeIdOf(el);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "CHANGE registry type mismatch: expected " + expected + ", element " + actual);
        }
        if (op.data() != null) {
            SyncRegistry.readSyncData(el, op.data());
        }
    }

    /**
     * Moves an element from the circuit identified by {@code sourceCircuitId} into {@code destCircuit} on
     * the client mirror. Returns the relocated element so the caller can apply any follow-up sync data.
     */
    private static CircuitElement rebindElement(
            ClientWorld world, ClientCircuit destCircuit, UUID elementId, UUID sourceCircuitId) {
        if (Objects.equals(destCircuit.getId(), sourceCircuitId)) {
            // No-op rebind to the same circuit (defensive — server should not emit this).
            CircuitElement el = destCircuit.findElement(elementId);
            if (el == null) {
                throw new IllegalArgumentException(
                        "REBIND target not found in " + sourceCircuitId + ": " + elementId);
            }
            return el;
        }
        Circuit src = world.findCircuit(sourceCircuitId);
        if (src == null) {
            throw new IllegalArgumentException("REBIND: missing source circuit " + sourceCircuitId);
        }
        CircuitElement el = src.findElement(elementId);
        if (el == null) {
            throw new IllegalArgumentException(
                    "REBIND: element " + elementId + " not in source circuit " + sourceCircuitId);
        }
        if (el instanceof CircuitNode n) {
            src.nodes().remove(n);
            destCircuit.nodes().add(n);
        } else if (el instanceof CircuitEdge e) {
            src.edges().remove(e);
            destCircuit.edges().add(e);
        } else if (el instanceof CircuitComponent c) {
            src.components().remove(c);
            destCircuit.components().add(c);
        } else {
            throw new IllegalArgumentException("REBIND: unknown element class " + el.getClass().getName());
        }
        el.setCircuit(destCircuit);
        return el;
    }

    private static void removeOne(ClientWorld world, ClientCircuit circuit, UUID id) {
        CircuitElement el = circuit.findElement(id);
        if (el == null) {
            throw new IllegalArgumentException("Unknown or unresolved element id: " + id);
        }
        if (el instanceof CircuitEdge e) {
            // Edge may be one of a component's internal struts on the client (load() restored the
            // backlink). The hasComponent guard on CircuitEdge.disconnect would refuse normal removal,
            // so the disconnect path explicitly nulls the component pointer first — the server has
            // already authorised this REMOVE so the client just executes it.
            world.disconnectWithoutRemoveEvent(e);
            return;
        }
        if (el instanceof CircuitComponent c) {
            // Just unlink the component shell from its master circuit. Each internal node and edge has
            // its own REMOVE delta in the same tick (emitted by the server's CircuitComponent.destroy);
            // letting the cascade run here would invent client-local circuits via a parallel seperate
            // pass and the subsequent REMOVE/REBIND deltas for those internals would point at circuits
            // the server never told us about. Reconcile() picks up the disappearance from
            // circuit.components() on the next frame.
            circuit.components().remove(c);
            return;
        }
        if (el instanceof CircuitNode n) {
            world.destroy(n);
            return;
        }
        throw new IllegalArgumentException("Unknown element type: " + el.getClass().getName());
    }
}
