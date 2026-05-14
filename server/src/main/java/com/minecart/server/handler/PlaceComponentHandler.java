package com.minecart.server.handler;

import com.minecart.foundation.World;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitNode;
import com.minecart.logic.ServerLevel;
import com.minecart.logic.ServerWorld;
import com.minecart.protocol.payload.PayloadHandler;
import com.minecart.protocol.payload.client.PlaceComponentPayload;
import com.minecart.registry.AllElementInfos;
import com.minecart.registry.CircuitElementRegistry;
import com.minecart.registry.CircuitElementType;
import com.minecart.registry.ComponentAnchorRegistry;
import com.minecart.variant.info.PositionInfo;
import com.minecart.variant.info.RotationInfo;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Server-side handler for {@link PlaceComponentPayload}: creates a fresh {@link CircuitComponent} of the
 * requested type (with its internal nodes/edges generated), stamps centre {@link PositionInfo} +
 * {@link RotationInfo}, then walks {@link ComponentAnchorRegistry#getAnchors} so each port node's
 * {@link PositionInfo} ends up at the rotated world coordinate of its anchor.
 *
 * <p>Silently no-ops on unknown world / unknown type / non-component type so misbehaving clients can't crash
 * the tick thread.
 */
public final class PlaceComponentHandler implements PayloadHandler<PlaceComponentPayload> {

    private final ServerLevel level;

    public PlaceComponentHandler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void handle(PlaceComponentPayload payload) {
        level.submit(() -> apply(payload));
    }

    private void apply(PlaceComponentPayload payload) {
        World world = level.findWorld(payload.getWorldId());
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        CircuitElementType<?> rawType = CircuitElementRegistry.getType(payload.getElementTypeId());
        if (rawType == null || rawType.isUnusual()) {
            return;
        }
        CircuitComponent comp;
        try {
            @SuppressWarnings("unchecked")
            CircuitElementType<? extends CircuitComponent> compType =
                    (CircuitElementType<? extends CircuitComponent>) rawType;
            comp = serverWorld.createComponent(compType);
        } catch (ClassCastException | IllegalStateException ex) {
            return;
        }
        PositionInfo centre = comp.getInfo(AllElementInfos.POSITION);
        if (centre == null) {
            centre = new PositionInfo();
            comp.setInfo(AllElementInfos.POSITION, centre);
        }
        centre.set(payload.getX(), payload.getY());

        RotationInfo rot = comp.getInfo(AllElementInfos.ROTATION);
        if (rot == null) {
            rot = new RotationInfo();
            comp.setInfo(AllElementInfos.ROTATION, rot);
        }
        rot.setAngle(payload.getAngle());

        Set<CircuitNode> anchored = new HashSet<>();
        for (ComponentAnchorRegistry.Anchor anchor : ComponentAnchorRegistry.getAnchors(rawType)) {
            CircuitNode port = comp.getPort(anchor.portIndex());
            if (port == null) {
                continue;
            }
            anchored.add(port);
            double[] xy = ComponentAnchorRegistry.worldPositionOf(
                    anchor, payload.getX(), payload.getY(), payload.getAngle());
            PositionInfo portPos = port.getInfo(AllElementInfos.POSITION);
            if (portPos == null) {
                portPos = new PositionInfo();
                port.setInfo(AllElementInfos.POSITION, portPos);
            }
            portPos.set(xy[0], xy[1]);
        }
        // Any internal node without a registered anchor (e.g. the BJTransistor's centre node) would
        // otherwise be stranded at the world origin because nothing else ever stamps PositionInfo onto
        // it. Default it to the component's centre so the renderer doesn't draw a spurious node at (0, 0).
        for (CircuitNode internal : comp.getNodes()) {
            if (anchored.contains(internal)) {
                continue;
            }
            PositionInfo pos = internal.getInfo(AllElementInfos.POSITION);
            if (pos == null) {
                pos = new PositionInfo();
                internal.setInfo(AllElementInfos.POSITION, pos);
            }
            pos.set(payload.getX(), payload.getY());
        }
    }
}
