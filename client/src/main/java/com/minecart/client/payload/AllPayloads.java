package com.minecart.client.payload;

/**
 * Aggregates handles for all {@link Payload} kinds (registration happens in each payload class, e.g. {@link ActionPayload#TYPE}).
 * Reference this class during startup if you want a single place to touch every kind — same role as
 * {@link com.minecart.registry.AllComponents}.
 */
public final class AllPayloads {

    public static final PayloadType<ActionPayload> ACTION = ActionPayload.TYPE;
    public static final PayloadType<CircuitTopologyPayload> CIRCUIT_TOPOLOGY = CircuitTopologyPayload.TYPE;
}
