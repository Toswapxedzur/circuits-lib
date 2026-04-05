package com.minecart.client.payload;

import com.minecart.client.payload.client.ActionPayload;
import com.minecart.client.payload.server.CircuitLifecyclePayload;
import com.minecart.client.payload.server.WorldLifecyclePayload;
import com.minecart.client.payload.server.CircuitSnapshotPayload;
import com.minecart.client.payload.server.CircuitElementPayload;

/**
 * Aggregates handles for all {@link com.minecart.client.payload.Payload} kinds (registration happens in each payload class, e.g. {@link ActionPayload#TYPE}).
 * Reference this class during startup if you want a single place to touch every kind — same role as
 * {@link com.minecart.registry.AllComponents}.
 */
public final class AllPayloads {
    public static final PayloadType<ActionPayload> ACTION = ActionPayload.TYPE;
    public static final PayloadType<CircuitElementPayload> CIRCUIT_ELEMENT = CircuitElementPayload.TYPE;
    public static final PayloadType<CircuitSnapshotPayload> CIRCUIT_SNAPSHOT = CircuitSnapshotPayload.TYPE;
    public static final PayloadType<CircuitLifecyclePayload> CIRCUIT_LIFECYCLE = CircuitLifecyclePayload.TYPE;
    public static final PayloadType<WorldLifecyclePayload> WORLD_LIFECYCLE = WorldLifecyclePayload.TYPE;
}
