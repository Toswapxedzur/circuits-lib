package com.minecart.display.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.minecart.display.DisplayApp;
import com.minecart.display.log.SessionLog;
import com.minecart.display.session.Sessions;
import com.minecart.display.world.WorldEntry;
import com.minecart.display.world.WorldManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class WorldListScreen extends ScreenAdapter {

    private static final Logger log = LoggerFactory.getLogger(WorldListScreen.class);

    private final DisplayApp app;
    private final Skin skin;
    private final WorldManager worlds;
    private final Stage stage;
    private final Table listTable;

    public WorldListScreen(DisplayApp app) {
        this.app = app;
        this.skin = app.getSkin();
        this.worlds = app.getWorlds();
        this.stage = new Stage(new ScreenViewport());
        this.listTable = new Table();
        this.listTable.top();
        buildUi();
        refresh();
    }

    private void buildUi() {
        Label title = new Label("Singleplayer Worlds", skin);
        title.setFontScale(1.4f);

        ScrollPane scroll = new ScrollPane(listTable, skin);
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false);

        TextButton create = new TextButton("Create World", skin);
        TextButton back   = new TextButton("Back", skin);

        create.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                showCreateDialog();
            }
        });
        back.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                app.setScreen(new MainMenuScreen(app));
            }
        });

        Table footer = new Table();
        footer.add(create).width(180f).height(44f).padRight(12f);
        footer.add(back).width(120f).height(44f);

        Table root = new Table();
        root.setFillParent(true);
        root.pad(20f);
        root.add(title).padBottom(16f).row();
        root.add(scroll).expand().fill().padBottom(12f).row();
        root.add(footer);
        stage.addActor(root);
    }

    private void refresh() {
        listTable.clearChildren();
        List<WorldEntry> entries = worlds.list();
        if (entries.isEmpty()) {
            Label empty = new Label("No worlds yet. Create one to get started.", skin, "muted");
            listTable.add(empty).pad(24f).row();
            return;
        }
        for (WorldEntry w : entries) {
            listTable.add(buildRow(w)).expandX().fillX().pad(4f).row();
        }
    }

    private Table buildRow(WorldEntry world) {
        Label name = new Label(world.name(), skin);
        name.setAlignment(Align.left);

        TextButton join = new TextButton("Join", skin);
        TextButton del  = new TextButton("Delete", skin);

        join.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                joinWorld(world);
            }
        });
        del.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                showDeleteConfirm(world);
            }
        });

        Table row = new Table(skin);
        row.setBackground("d_row");
        row.pad(8f);
        row.add(name).expandX().fillX().padLeft(8f);
        row.add(join).width(80f).height(36f).padLeft(8f);
        row.add(del).width(80f).height(36f).padLeft(8f);
        return row;
    }

    /**
     * Boots an integrated server, connects a client to it via Netty's in-process LocalChannel, and pushes
     * {@link GameScreen}. Failures show a log entry; the user stays on the world list.
     */
    private void joinWorld(WorldEntry world) {
        // Open the session log BEFORE booting the integrated server so the load path
        // (WorldStorage.load, listener attach, initial-snapshot send) is captured. If the session
        // setup fails we still want the failure in the file.
        SessionLog.begin("sp_" + world.name());
        Sessions.Session session;
        try {
            session = Sessions.singleplayer(world);
        } catch (Exception ex) {
            log.error("Failed to start singleplayer session for '{}'", world.name(), ex);
            SessionLog.end();
            return;
        }
        app.setScreen(new GameScreen(app, world.name(), session.level(), session.connection(), session.integrated()));
    }

    private void showCreateDialog() {
        TextField nameField = new TextField("", skin);
        nameField.setMessageText("World name");

        Dialog dialog = new Dialog("Create World", skin) {
            @Override
            protected void result(Object obj) {
                if (Boolean.TRUE.equals(obj)) {
                    String entered = nameField.getText();
                    WorldEntry created = worlds.create(entered);
                    if (created != null) {
                        joinWorld(created);
                    } else {
                        log.warn("Invalid or duplicate world name: '{}'", entered);
                    }
                }
            }
        };
        dialog.getContentTable().pad(12f).defaults().pad(6f);
        dialog.getContentTable().add(new Label("Name:", skin));
        dialog.getContentTable().add(nameField).width(280f).row();
        dialog.button("Create", Boolean.TRUE);
        dialog.button("Cancel", Boolean.FALSE);
        dialog.key(13, Boolean.TRUE);   // Enter
        dialog.key(131, Boolean.FALSE); // Esc
        dialog.show(stage);
        stage.setKeyboardFocus(nameField);
    }

    private void showDeleteConfirm(WorldEntry world) {
        Dialog dialog = new Dialog("Delete World", skin) {
            @Override
            protected void result(Object obj) {
                if (Boolean.TRUE.equals(obj)) {
                    worlds.delete(world);
                    refresh();
                }
            }
        };
        dialog.getContentTable().pad(12f);
        dialog.getContentTable().add(new Label("Permanently delete '" + world.name() + "'?", skin));
        dialog.button("Delete", Boolean.TRUE);
        dialog.button("Cancel", Boolean.FALSE);
        dialog.key(131, Boolean.FALSE);
        dialog.show(stage);
    }

    @Override public void show() {
        Gdx.input.setInputProcessor(stage);
        refresh();
    }

    @Override public void render(float dt) {
        Gdx.gl.glClearColor(0.08f, 0.09f, 0.11f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        stage.act(dt);
        stage.draw();
    }

    @Override public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
        relayoutOpenDialogs();
    }

    private void relayoutOpenDialogs() {
        for (Actor actor : stage.getActors()) {
            if (actor instanceof Dialog dialog) {
                fitDialogToStage(dialog);
            }
        }
    }

    private void fitDialogToStage(Dialog dialog) {
        dialog.invalidateHierarchy();
        dialog.pack();
        float margin = 12f;
        float maxWidth = Math.max(120f, stage.getWidth() - margin * 2f);
        float maxHeight = Math.max(80f, stage.getHeight() - margin * 2f);
        dialog.setSize(Math.min(dialog.getWidth(), maxWidth), Math.min(dialog.getHeight(), maxHeight));
        dialog.setPosition(
                (stage.getWidth() - dialog.getWidth()) / 2f,
                (stage.getHeight() - dialog.getHeight()) / 2f);
    }

    @Override public void dispose() {
        stage.dispose();
    }
}
