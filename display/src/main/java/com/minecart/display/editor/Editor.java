package com.minecart.display.editor;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.minecart.client.logic.ClientLevel;
import com.minecart.client.network.ClientConnection;
import com.minecart.display.render.WorldStage;
import com.minecart.logic.CircuitNode;
import com.minecart.protocol.payload.client.ConnectEdgePayload;
import com.minecart.protocol.payload.client.PlaceComponentPayload;
import com.minecart.protocol.payload.client.PlaceNodePayload;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Editor state machine for the singleplayer (and integrated-multiplayer) world view. Owns the currently
 * selected {@link EditorTool} and translates user input into protocol payloads sent through
 * {@link ClientConnection}.
 *
 * <p>Click semantics depend on the active tool:
 * <ul>
 *   <li>{@link EditorTool.Idle}: clicks are ignored.</li>
 *   <li>{@link EditorTool.PlaceNode}: left-click sends {@link PlaceNodePayload} at the cursor's world point.</li>
 *   <li>{@link EditorTool.PlaceComponent}: left-click sends {@link PlaceComponentPayload}; {@code R} rotates
 *       by 15° (Shift+R = 1°).</li>
 *   <li>{@link EditorTool.ConnectEdge}: first left-click selects a node endpoint, second click on another node
 *       sends {@link ConnectEdgePayload}; {@code Esc} cancels the half-connection.</li>
 * </ul>
 *
 * <p>Drag-from-palette is supported: after a {@link #beginDragGhost} the next click on the world drops the
 * element at the cursor and clears the tool back to {@link EditorTool.Idle}.
 */
public class Editor extends InputAdapter {

    private static final double ROT_STEP_BIG = Math.toRadians(15.0);
    private static final double ROT_STEP_FINE = Math.toRadians(1.0);

    private final ClientLevel clientLevel;
    private final ClientConnection connection;
    private final WorldStage stage;
    /** Supplies the currently-selected world's id at click time; {@code null} = no selection. */
    private final Supplier<UUID> selectedWorldId;
    /** Notified when the user tries to place but no world is selected (so the UI can show a warning). */
    private final Runnable onNoWorld;

    private EditorTool tool = new EditorTool.Idle();
    /** When true, completing one placement reverts the tool to Idle (drag-from-palette flow). */
    private boolean oneShotDrop;

    public Editor(ClientLevel clientLevel, ClientConnection connection, WorldStage stage) {
        this(clientLevel, connection, stage, () -> firstWorldIdOf(clientLevel), () -> {});
    }

    public Editor(ClientLevel clientLevel,
                  ClientConnection connection,
                  WorldStage stage,
                  Supplier<UUID> selectedWorldId,
                  Runnable onNoWorld) {
        this.clientLevel = clientLevel;
        this.connection = connection;
        this.stage = stage;
        this.selectedWorldId = selectedWorldId;
        this.onNoWorld = onNoWorld;
    }

    private static UUID firstWorldIdOf(ClientLevel level) {
        for (var w : level.getWorlds()) {
            return w.getId();
        }
        return null;
    }

    public EditorTool getTool() {
        return tool;
    }

    /** Switches the tool. {@code null} maps to {@link EditorTool.Idle}. */
    public void setTool(EditorTool tool) {
        this.tool = tool != null ? tool : new EditorTool.Idle();
        this.oneShotDrop = false;
    }

    /** Selects a tool that resets back to Idle after the next successful drop (drag from palette). */
    public void setToolOneShot(EditorTool tool) {
        setTool(tool);
        this.oneShotDrop = true;
    }

    public void beginDragGhost(EditorTool tool) {
        setToolOneShot(tool);
    }

    public void cancel() {
        setTool(new EditorTool.Idle());
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (button != Input.Buttons.LEFT) {
            return false;
        }
        float[] world = stage.screenToWorld(screenX, screenY);
        float wx = world[0];
        float wy = world[1];
        if (tool instanceof EditorTool.PlaceNode pn) {
            sendPlaceNode(pn, wx, wy);
            postPlacement();
            return true;
        }
        if (tool instanceof EditorTool.PlaceComponent pc) {
            sendPlaceComponent(pc, wx, wy);
            postPlacement();
            return true;
        }
        if (tool instanceof EditorTool.ConnectEdge ce) {
            CircuitNode node = nodeAt(wx, wy);
            if (node == null) {
                return false;
            }
            if (ce.firstNodeId() == null) {
                tool = ce.withFirst(node.getId());
            } else if (!ce.firstNodeId().equals(node.getId())) {
                sendConnectEdge(ce, ce.firstNodeId(), node.getId());
                tool = ce.withFirst(null);
                if (oneShotDrop) {
                    cancel();
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean keyDown(int keycode) {
        if (keycode == Input.Keys.ESCAPE) {
            if (!(tool instanceof EditorTool.Idle)) {
                cancel();
                return true;
            }
            return false;
        }
        if (keycode == Input.Keys.R && tool instanceof EditorTool.PlaceComponent pc) {
            boolean fine = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
                    || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
            double step = fine ? ROT_STEP_FINE : ROT_STEP_BIG;
            tool = pc.withAngle(pc.angle() + step);
            return true;
        }
        return false;
    }

    private void postPlacement() {
        if (oneShotDrop) {
            cancel();
        }
    }

    private CircuitNode nodeAt(float wx, float wy) {
        var actor = stage.findNodeActorAt(wx, wy);
        return actor != null ? actor.getNode() : null;
    }

    /** Resolves the world the next placement should target, firing the no-world callback if missing. */
    private UUID resolveTargetWorld() {
        UUID world = selectedWorldId.get();
        if (world == null || clientLevel.findWorld(world) == null) {
            onNoWorld.run();
            return null;
        }
        return world;
    }

    private void sendPlaceNode(EditorTool.PlaceNode pn, float wx, float wy) {
        UUID world = resolveTargetWorld();
        if (world == null) {
            return;
        }
        connection.send(new PlaceNodePayload(world, pn.type().getTypeId(), wx, wy));
    }

    private void sendPlaceComponent(EditorTool.PlaceComponent pc, float wx, float wy) {
        UUID world = resolveTargetWorld();
        if (world == null) {
            return;
        }
        connection.send(new PlaceComponentPayload(world, pc.type().getTypeId(), wx, wy, pc.angle()));
    }

    private void sendConnectEdge(EditorTool.ConnectEdge ce, UUID start, UUID end) {
        UUID world = resolveTargetWorld();
        if (world == null) {
            return;
        }
        connection.send(new ConnectEdgePayload(world, ce.type().getTypeId(), start, end));
    }
}
