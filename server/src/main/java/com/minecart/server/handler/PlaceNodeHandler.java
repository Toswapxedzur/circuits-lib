package com.minecart.server.handler;

import com.minecart.foundation.World;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.PlaceNodePayload;
import com.minecart.registry.AllElementInfos;
import com.minecart.registry.CircuitElementRegistry;
import com.minecart.registry.CircuitElementType;
import com.minecart.variant.info.PositionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Server-side handler for {@link PlaceNodePayload}: queues a job onto the {@link ServerLevel} action queue
 * that creates a free {@link CircuitNode} of the requested type and stamps {@link PositionInfo} onto it.
 *
 * <p>Silently no-ops on unknown world / unknown type / unusual type — placement requests from buggy or
 * out-of-date clients should never crash the simulation thread.
 */
public final class PlaceNodeHandler implements PayloadHandler<PlaceNodePayload> {

    private static final Logger log = LoggerFactory.getLogger(PlaceNodeHandler.class);

    private final ServerLevel level;

    public PlaceNodeHandler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void handle(PlaceNodePayload payload) {
        // The dispatcher already marshals onto the tick thread; apply directly (no second submit hop).
        apply(payload);
    }

    private void apply(PlaceNodePayload payload) {
        World world = level.findWorld(payload.getWorldId());
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        CircuitElementType<?> rawType;
        try {
            rawType = CircuitElementRegistry.getType(payload.getElementTypeId());
        } catch (IllegalArgumentException ex) {
            log.debug("place-node: unknown element type '{}', dropping", payload.getElementTypeId());
            return;
        }
        if (rawType.isUnusual()) {
            return;
        }
        CircuitNode node;
        try {
            @SuppressWarnings("unchecked")
            CircuitElementType<? extends CircuitNode> nodeType = (CircuitElementType<? extends CircuitNode>) rawType;
            node = serverWorld.createNode(nodeType);
        } catch (ClassCastException ex) {
            return;
        }
        PositionInfo pos = node.getInfo(AllElementInfos.POSITION);
        if (pos == null) {
            pos = new PositionInfo();
            node.setInfo(AllElementInfos.POSITION, pos);
        }
        pos.set(payload.getX(), payload.getY());
    }
}
