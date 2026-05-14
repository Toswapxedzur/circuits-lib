package com.minecart.display.input;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.minecart.client.network.ClientConnection;
import com.minecart.display.editor.Editor;
import com.minecart.display.editor.EditorTool;
import com.minecart.display.render.WorldStage;
import com.minecart.logic.CircuitComponent;
import com.minecart.logic.CircuitElement;
import com.minecart.logic.CircuitNode;
import com.minecart.protocol.payload.client.DeleteElementPayload;
import com.minecart.protocol.payload.client.MoveElementPayload;
import com.minecart.registry.AllElementInfos;
import com.minecart.variant.info.PositionInfo;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cursor-driven hover + drag + trashcan input adapter. Plugs into the screen's
 * {@link com.badlogic.gdx.InputMultiplexer} between the UI stage and the camera/editor.
 *
 * <h2>Hover</h2>
 * Every {@link #mouseMoved} reports the topmost {@link CircuitElement} under the cursor to
 * {@link WorldStage#setHoveredElementId(UUID)} so the renderer can apply a yellow tint. Returns {@code false}
 * so downstream processors still see the move.
 *
 * <h2>Drag</h2>
 * Left {@link #touchDown} over a draggable element (free {@link CircuitNode} or {@link CircuitComponent})
 * captures it <em>only while the editor tool is Idle</em>, so a placement tool always wins click priority.
 * During {@link #touchDragged} the element's local {@link PositionInfo} is rewritten so the actor visually
 * follows the cursor instantly — the authoritative server update lands later through the normal sync
 * pipeline. On {@link #touchUp} a {@link MoveElementPayload} (or {@link DeleteElementPayload} when released
 * over the trashcan rectangle) is sent.
 *
 * <h2>Trashcan</h2>
 * The trashcan rectangle is supplied as a screen-space {@link TrashBoundsSupplier} so the screen layout can
 * compute it from the UI stage every frame. The same supplier returns {@code null} when no rectangle should
 * be considered active (e.g. UI not yet built), in which case the trash check is skipped.
 */
public class DragController extends InputAdapter {

    /** Screen-space bounds of the trashcan, or {@code null} if unknown / not active. */
    @FunctionalInterface
    public interface TrashBoundsSupplier {
        /** Returns {@code [x, y, w, h]} in libGDX screen coordinates (y up), or {@code null}. */
        float[] get();
    }

    private final WorldStage stage;
    private final ClientConnection connection;
    private final Editor editor;
    private final Supplier<UUID> selectedWorldId;
    private final TrashBoundsSupplier trashBounds;
    /** Notified with {@code true} on drag start and {@code false} on drag end so the UI can show the can. */
    private final java.util.function.Consumer<Boolean> onDragStateChanged;

    private UUID draggingId;
    private boolean isComponent;
    /** World-space cursor position at drag start. */
    private float dragStartWorldX;
    private float dragStartWorldY;
    /** Element's original position at drag start (component centre or free node position). */
    private double originX;
    private double originY;
    /** Original positions of the component's internal nodes captured at drag start. */
    private final java.util.Map<UUID, double[]> originalNodePositions = new java.util.HashMap<>();
    /** Set after a drag actually moves so a stationary click doesn't generate a no-op MoveElement. */
    private boolean movedSinceDown;

    public DragController(WorldStage stage,
                          ClientConnection connection,
                          Editor editor,
                          Supplier<UUID> selectedWorldId,
                          TrashBoundsSupplier trashBounds,
                          java.util.function.Consumer<Boolean> onDragStateChanged) {
        this.stage = stage;
        this.connection = connection;
        this.editor = editor;
        this.selectedWorldId = selectedWorldId;
        this.trashBounds = trashBounds;
        this.onDragStateChanged = onDragStateChanged != null ? onDragStateChanged : v -> {};
    }

    public boolean isDragging() {
        return draggingId != null;
    }

    @Override
    public boolean mouseMoved(int screenX, int screenY) {
        updateHoverFromScreen(screenX, screenY);
        return false;
    }

    private void updateHoverFromScreen(int screenX, int screenY) {
        float[] w = stage.screenToWorld(screenX, screenY);
        CircuitElement el = stage.hitTestWorld(w[0], w[1]);
        // Edges have no PositionInfo, so even if hitTestWorld returns one later we shouldn't claim hover on
        // it — keep the check explicit so adding edge hit-testing in WorldStage stays a non-issue here.
        if (el instanceof CircuitComponent || el instanceof CircuitNode) {
            stage.setHoveredElementId(el.getId());
        } else {
            stage.setHoveredElementId(null);
        }
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (button != Input.Buttons.LEFT) {
            return false;
        }
        // Refuse to claim the click while a placement tool is active so the editor keeps placement
        // ergonomics. The user can press Esc to drop the tool and then drag existing elements.
        if (!(editor.getTool() instanceof EditorTool.Idle)) {
            return false;
        }
        float[] w = stage.screenToWorld(screenX, screenY);
        CircuitElement el = stage.hitTestWorld(w[0], w[1]);
        if (el == null) {
            return false;
        }
        if (el instanceof CircuitComponent comp) {
            beginDrag(comp);
            dragStartWorldX = w[0];
            dragStartWorldY = w[1];
            return true;
        }
        if (el instanceof CircuitNode node) {
            // Internal port nodes belong to their component's pose; route drag through the parent instead so
            // anchor offsets stay correct. If the user clicks an internal port directly, fall back to its
            // component.
            if (node.getComponent() != null) {
                beginDrag(node.getComponent());
            } else {
                beginDragFreeNode(node);
            }
            dragStartWorldX = w[0];
            dragStartWorldY = w[1];
            return true;
        }
        return false;
    }

    private void beginDrag(CircuitComponent comp) {
        draggingId = comp.getId();
        isComponent = true;
        movedSinceDown = false;
        PositionInfo pos = comp.getInfo(AllElementInfos.POSITION);
        originX = pos != null ? pos.getX() : 0.0;
        originY = pos != null ? pos.getY() : 0.0;
        originalNodePositions.clear();
        for (CircuitNode n : comp.getNodes()) {
            PositionInfo np = n.getInfo(AllElementInfos.POSITION);
            originalNodePositions.put(n.getId(),
                    new double[]{np != null ? np.getX() : 0.0, np != null ? np.getY() : 0.0});
        }
        stage.setDraggedElementId(draggingId);
        stage.setDraggedOverTrash(false);
        onDragStateChanged.accept(Boolean.TRUE);
    }

    private void beginDragFreeNode(CircuitNode node) {
        draggingId = node.getId();
        isComponent = false;
        movedSinceDown = false;
        PositionInfo pos = node.getInfo(AllElementInfos.POSITION);
        originX = pos != null ? pos.getX() : 0.0;
        originY = pos != null ? pos.getY() : 0.0;
        originalNodePositions.clear();
        stage.setDraggedElementId(draggingId);
        stage.setDraggedOverTrash(false);
        onDragStateChanged.accept(Boolean.TRUE);
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (draggingId == null) {
            return false;
        }
        float[] w = stage.screenToWorld(screenX, screenY);
        double dx = w[0] - dragStartWorldX;
        double dy = w[1] - dragStartWorldY;
        if (dx != 0 || dy != 0) {
            movedSinceDown = true;
        }
        if (isComponent) {
            applyComponentDragDelta(dx, dy);
        } else {
            applyFreeNodeDragDelta(dx, dy);
        }
        stage.setDraggedOverTrash(isOverTrash(screenX, screenY));
        return true;
    }

    private void applyComponentDragDelta(double dx, double dy) {
        CircuitComponent comp = findComponent(draggingId);
        if (comp == null) {
            return;
        }
        PositionInfo pos = comp.getInfo(AllElementInfos.POSITION);
        if (pos == null) {
            pos = new PositionInfo();
            comp.setInfo(AllElementInfos.POSITION, pos);
        }
        pos.set(originX + dx, originY + dy);
        // Translate every captured internal node by the same delta so port edges follow the body instead of
        // stretching while the user drags. The server will recompute proper anchor offsets when the
        // MoveElementPayload lands.
        for (CircuitNode n : comp.getNodes()) {
            double[] orig = originalNodePositions.get(n.getId());
            if (orig == null) {
                continue;
            }
            PositionInfo np = n.getInfo(AllElementInfos.POSITION);
            if (np == null) {
                np = new PositionInfo();
                n.setInfo(AllElementInfos.POSITION, np);
            }
            np.set(orig[0] + dx, orig[1] + dy);
        }
    }

    private void applyFreeNodeDragDelta(double dx, double dy) {
        CircuitNode node = findFreeNode(draggingId);
        if (node == null) {
            return;
        }
        PositionInfo pos = node.getInfo(AllElementInfos.POSITION);
        if (pos == null) {
            pos = new PositionInfo();
            node.setInfo(AllElementInfos.POSITION, pos);
        }
        pos.set(originX + dx, originY + dy);
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (button != Input.Buttons.LEFT || draggingId == null) {
            return false;
        }
        UUID worldId = selectedWorldId.get();
        UUID id = draggingId;
        boolean overTrash = isOverTrash(screenX, screenY);
        boolean moved = movedSinceDown;
        // Clear local drag state before sending so the trashcan + tint disappear immediately.
        draggingId = null;
        stage.setDraggedElementId(null);
        stage.setDraggedOverTrash(false);
        onDragStateChanged.accept(Boolean.FALSE);

        if (worldId == null) {
            return true;
        }
        if (overTrash) {
            connection.send(new DeleteElementPayload(worldId, id));
            return true;
        }
        if (!moved) {
            // Pure click on the element — nothing else uses it yet (selection not implemented), so just
            // swallow it. Sending a zero-distance MoveElement would still be safe but wastes a roundtrip.
            return true;
        }
        if (isComponent) {
            CircuitComponent comp = findComponent(id);
            if (comp != null) {
                PositionInfo p = comp.getInfo(AllElementInfos.POSITION);
                if (p != null) {
                    connection.send(new MoveElementPayload(worldId, id, p.getX(), p.getY()));
                }
            }
        } else {
            CircuitNode node = findFreeNode(id);
            if (node != null) {
                PositionInfo p = node.getInfo(AllElementInfos.POSITION);
                if (p != null) {
                    connection.send(new MoveElementPayload(worldId, id, p.getX(), p.getY()));
                }
            }
        }
        return true;
    }

    private boolean isOverTrash(int screenX, int screenY) {
        float[] bounds = trashBounds.get();
        if (bounds == null) {
            return false;
        }
        // The supplier returns coordinates in Scene2D screen space (origin bottom-left, y up). LibGDX's
        // input touch coordinates use top-left origin, so we flip y to match before comparing.
        float yFromBottom = com.badlogic.gdx.Gdx.graphics.getHeight() - screenY;
        return screenX >= bounds[0] && screenX <= bounds[0] + bounds[2]
                && yFromBottom >= bounds[1] && yFromBottom <= bounds[1] + bounds[3];
    }

    private CircuitComponent findComponent(UUID id) {
        var actor = stage.getComponentActor(id);
        return actor != null ? actor.getComponent() : null;
    }

    private CircuitNode findFreeNode(UUID id) {
        var actor = stage.getNodeActor(id);
        return actor != null ? actor.getNode() : null;
    }
}
