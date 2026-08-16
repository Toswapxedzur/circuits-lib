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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Server-side handler for {@link PlaceComponentPayload}: creates a fresh {@link CircuitComponent} of the
 * requested type (with its internal nodes/edges generated), stamps pivot/anchor {@link PositionInfo} +
 * {@link RotationInfo}, then walks {@link ComponentAnchorRegistry#getAnchors} so each port node's
 * {@link PositionInfo} ends up at the rotated world coordinate of its anchor.
 *
 * <p>Silently no-ops on unknown world / unknown type / non-component type so misbehaving clients can't crash
 * the tick thread.
 */
public final class PlaceComponentHandler implements PayloadHandler<PlaceComponentPayload> {

    private static final Logger log = LoggerFactory.getLogger(PlaceComponentHandler.class);

    private final ServerLevel level;

    public PlaceComponentHandler(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
    }

    @Override
    public void handle(PlaceComponentPayload payload) {
        // The dispatcher already marshals onto the tick thread; apply directly (no second submit hop).
        apply(payload);
    }

    private void apply(PlaceComponentPayload payload) {
        World world = level.findWorld(payload.getWorldId());
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        CircuitElementType<?> rawType;
        try {
            rawType = CircuitElementRegistry.getType(payload.getElementTypeId());
        } catch (IllegalArgumentException ex) {
            log.debug("place-component: unknown element type '{}', dropping", payload.getElementTypeId());
            return;
        }
        if (rawType.isUnusual()) {
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
        PositionInfo pivot = comp.getInfo(AllElementInfos.POSITION);
        if (pivot == null) {
            pivot = new PositionInfo();
            comp.setInfo(AllElementInfos.POSITION, pivot);
        }
        pivot.set(payload.getX(), payload.getY());

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
            lockToParent(portPos);
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
            lockToParent(pos);
        }
    }

    /**
     * Marks {@code pos} as not user-movable and disables further toggling. Component internal nodes are
     * tied to their parent's pose by anchor offsets that participate in the device's electrical model;
     * the editor must always route their motion through the parent, never let the user drag a single port
     * off its anchor. {@code canChangeFix=false} means even a future settings panel can't unlock them.
     */
    private static void lockToParent(PositionInfo pos) {
        // setFixed first while canChangeFix is still its default (true), then lock the toggle so the lock
        // itself can't be undone.
        pos.setFixed(true);
        pos.setCanChangeFix(false);
    }
}
