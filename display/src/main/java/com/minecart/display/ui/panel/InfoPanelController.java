package com.minecart.display.ui.panel;

import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.minecart.client.network.ClientConnection;
import com.minecart.display.render.WorldStage;
import com.minecart.logic.CircuitElement;
import com.minecart.protocol.payload.client.DragBeginPayload;
import com.minecart.protocol.payload.client.DragEndPayload;
import com.minecart.protocol.payload.client.ElementInfoUpdatePayload;
import com.minecart.ui.panel.InfoPanelRegistry;
import com.minecart.ui.panel.InfoPanelSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Glue between an element click and the rendered info panel.
 *
 * <p>Owns the single open dialog so the design's "only one edit panel could exist" rule lives here:
 * a fresh {@link #openFor} closes the previous dialog (if any) before showing the new one. Closure
 * happens whether the user clicked Save or Cancel — the dialog calls back into
 * {@link #onDialogClosed} from both code paths.
 *
 * <p>On Save, builds an {@link ElementInfoUpdatePayload} from the renderer's snapshot and ships
 * it on the supplied {@link ClientConnection}. The local element state is intentionally NOT
 * mutated here — server-side listeners decide what to apply, and the existing snapshot/delta
 * sync replicates any accepted state changes back to the client. That's why an invalid value
 * "reopens the panel and shows the old data": the local mirror was never optimistically updated.
 */
public final class InfoPanelController {

    private static final Logger log = LoggerFactory.getLogger(InfoPanelController.class);

    private final Stage uiStage;
    private final Skin skin;
    private final ClientConnection connection;
    /**
     * Optional reference to the world stage so the controller can paint the edited element with a
     * distinctive tint while its panel is open. Tints are best-effort: when no stage is wired
     * (tests, headless smoke), the lock still works on the protocol side (drag-lease + DragController
     * refusal); the user just doesn't get a visual highlight.
     */
    private final WorldStage worldStage;

    private Dialog openDialog;
    /**
     * Element currently being edited (i.e. its panel is open). {@code null} when no panel is open.
     * Exposed via {@link #getEditingElementId()} so the {@link com.minecart.display.input.DragController}
     * can refuse to start a drag on it — that's the client-side enforcement of "an element is
     * forcibly locked while its panel is open".
     */
    private UUID editingElementId;
    /**
     * World containing the edited element, captured at open-time so close-time can route the
     * {@link DragEndPayload} to the same world without re-querying the screen's selection (which
     * may have changed by then).
     */
    private UUID editWorldId;
    /**
     * Gesture id under which the server holds the edit lease. Fresh UUID per panel open so multiple
     * sequential edits don't pollute each other's leases on the server registry.
     */
    private UUID editGestureId;

    public InfoPanelController(Stage uiStage, Skin skin, ClientConnection connection) {
        this(uiStage, skin, connection, null);
    }

    public InfoPanelController(Stage uiStage, Skin skin, ClientConnection connection,
                               WorldStage worldStage) {
        this.uiStage = Objects.requireNonNull(uiStage, "uiStage");
        this.skin = Objects.requireNonNull(skin, "skin");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.worldStage = worldStage;
    }

    /**
     * Opens a panel for {@code element} inside {@code worldId}, or no-ops if no
     * {@link InfoPanelDefinition} is registered for its type. Closes any currently open panel
     * first so we never have two stacked dialogs.
     */
    public void openFor(UUID worldId, CircuitElement element) {
        if (worldId == null || element == null) {
            return;
        }
        // Composed schema = cross-cutting fragments (position / rotation / lock) + the type
        // specific definition (if registered). Returns null when nothing would contribute, which
        // preserves the legacy "no panel for this element" behaviour for elements that have
        // neither fragments nor a type-specific def.
        InfoPanelSchema schema;
        try {
            schema = InfoPanelRegistry.buildSchema(element);
        } catch (Throwable t) {
            log.warn("InfoPanelRegistry failed to build a schema for {}", element.getRegistryTypeId(), t);
            return;
        }
        if (schema == null) {
            return;
        }

        closeOpen();
        UUID elementId = element.getId();
        // Open the edit lease BEFORE showing the dialog so the lease is in effect for the entire
        // visible lifetime of the panel. The server will refuse combine cascades + (future) drags
        // targeting the leased element from any gesture other than this edit gesture.
        acquireEditLease(worldId, elementId);
        // Save closure: clears openDialog AFTER sending so the next openFor() doesn't try to hide
        // an already-detached dialog. Cancel closure happens through the dialog's own hide() path;
        // pollClosed() (called every render frame from GameScreen) reconciles openDialog → null
        // when the dialog detaches from its stage without going through Save.
        openDialog = InfoPanelRenderer.open(uiStage, skin, schema, snapshot -> {
            try {
                connection.send(new ElementInfoUpdatePayload(worldId, elementId, snapshot));
            } catch (Throwable t) {
                log.warn("Failed to send ElementInfoUpdatePayload for element {}", elementId, t);
            } finally {
                openDialog = null;
                releaseEditLease();
            }
        });
    }

    /**
     * Closes whatever panel might be open. Idempotent. Used by the editor when the user quits the
     * world or when an element gets deleted from under the panel (caller has to check that).
     */
    public void closeOpen() {
        if (openDialog != null) {
            try {
                openDialog.hide();
            } catch (Throwable ignored) {
                // hide() can throw if the dialog was already removed from the stage (e.g. the
                // stage was disposed first); we just want to drop the reference either way.
            }
            openDialog = null;
            releaseEditLease();
        }
    }

    /**
     * If the open dialog is no longer attached to any stage (Cancel/Save path already removed it
     * from the actor tree but our reference is stale), drop the reference. Called from the editor
     * render loop so the "one panel at a time" rule recovers cleanly after a Cancel click.
     */
    public void pollClosed() {
        if (openDialog != null && openDialog.getStage() == null) {
            openDialog = null;
            // Cancel path: the dialog detached itself from the stage without going through the
            // save callback, so releaseEditLease() didn't fire. Catch that here so the lease
            // (and the tint) doesn't stick around after a Cancel click.
            releaseEditLease();
        }
    }

    public boolean isOpen() {
        return openDialog != null && openDialog.getStage() != null;
    }

    /**
     * @return the id of the element whose panel is currently open, or {@code null} when no panel is
     *     open. {@link com.minecart.display.input.DragController} polls this to refuse drags on the
     *     edited element so the user can't move it out from under their own panel.
     */
    public UUID getEditingElementId() {
        return editingElementId;
    }

    // --- edit lease + tint plumbing ------------------------------------------------------------

    private void acquireEditLease(UUID worldId, UUID elementId) {
        editingElementId = elementId;
        editWorldId = worldId;
        editGestureId = UUID.randomUUID();
        try {
            connection.send(new DragBeginPayload(worldId, editGestureId, List.of(elementId)));
        } catch (Throwable t) {
            log.warn("Failed to send DragBeginPayload for edit lease on element {}", elementId, t);
        }
        if (worldStage != null) {
            worldStage.setEditingElementId(elementId);
        }
    }

    private void releaseEditLease() {
        if (editingElementId == null) {
            return;
        }
        UUID worldId = editWorldId;
        UUID gestureId = editGestureId;
        editingElementId = null;
        editWorldId = null;
        editGestureId = null;
        if (worldStage != null) {
            worldStage.setEditingElementId(null);
        }
        if (worldId != null && gestureId != null) {
            try {
                connection.send(new DragEndPayload(worldId, gestureId));
            } catch (Throwable t) {
                log.warn("Failed to send DragEndPayload for edit lease (gesture {})", gestureId, t);
            }
        }
    }
}
