package com.minecart.display.ui.panel;

import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.minecart.client.network.ClientConnection;
import com.minecart.logic.CircuitElement;
import com.minecart.protocol.payload.client.ElementInfoUpdatePayload;
import com.minecart.ui.panel.InfoPanelDefinition;
import com.minecart.ui.panel.InfoPanelRegistry;
import com.minecart.ui.panel.InfoPanelSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private Dialog openDialog;

    public InfoPanelController(Stage uiStage, Skin skin, ClientConnection connection) {
        this.uiStage = Objects.requireNonNull(uiStage, "uiStage");
        this.skin = Objects.requireNonNull(skin, "skin");
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * Opens a panel for {@code element} inside {@code worldId}, or no-ops if no
     * {@link InfoPanelDefinition} is registered for its type. Closes any currently open panel
     * first so we never have two stacked dialogs.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void openFor(UUID worldId, CircuitElement element) {
        if (worldId == null || element == null) {
            return;
        }
        InfoPanelDefinition def = InfoPanelRegistry.getById(element.getRegistryTypeId());
        if (def == null) {
            // No panel registered for this element type — silently skip. This is the common case
            // for the built-in CircuitNode "connection" type, etc., until somebody registers one.
            return;
        }
        InfoPanelSchema schema;
        try {
            // The cast is unchecked because InfoPanelRegistry erases the type parameter for storage;
            // we trust that whoever registered the definition keyed it under the right type id.
            schema = ((InfoPanelDefinition<CircuitElement>) def).build(element);
        } catch (Throwable t) {
            log.warn("InfoPanelDefinition for {} failed to build a schema", element.getRegistryTypeId(), t);
            return;
        }

        closeOpen();
        UUID elementId = element.getId();
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
        }
    }

    public boolean isOpen() {
        return openDialog != null && openDialog.getStage() != null;
    }
}
