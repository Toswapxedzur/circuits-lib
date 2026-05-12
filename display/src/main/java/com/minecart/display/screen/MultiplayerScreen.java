package com.minecart.display.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.graphics.GL20;
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
import com.minecart.display.server.ServerEntry;
import com.minecart.display.server.ServerManager;
import com.minecart.display.session.Sessions;

public class MultiplayerScreen extends ScreenAdapter {

    private final DisplayApp app;
    private final Skin skin;
    private final ServerManager servers;
    private final Stage stage;
    private final Table listTable;

    public MultiplayerScreen(DisplayApp app) {
        this.app = app;
        this.skin = app.getSkin();
        this.servers = app.getServers();
        this.stage = new Stage(new ScreenViewport());
        this.listTable = new Table();
        this.listTable.top();
        buildUi();
    }

    private void buildUi() {
        Label title = new Label("Multiplayer Servers", skin);
        title.setFontScale(1.4f);

        ScrollPane scroll = new ScrollPane(listTable, skin);
        scroll.setFadeScrollBars(false);
        scroll.setScrollingDisabled(true, false);

        TextButton add     = new TextButton("Add Server", skin);
        TextButton refresh = new TextButton("Refresh", skin);
        TextButton back    = new TextButton("Back", skin);

        add.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                showAddDialog();
            }
        });
        refresh.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                servers.pingAll();
                rebuildRows();
            }
        });
        back.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                app.setScreen(new MainMenuScreen(app));
            }
        });

        Table footer = new Table();
        footer.add(add).width(160f).height(44f).padRight(12f);
        footer.add(refresh).width(120f).height(44f).padRight(12f);
        footer.add(back).width(120f).height(44f);

        Table root = new Table();
        root.setFillParent(true);
        root.pad(20f);
        root.add(title).padBottom(16f).row();
        root.add(scroll).expand().fill().padBottom(12f).row();
        root.add(footer);
        stage.addActor(root);
    }

    /**
     * Rebuilds the row list from the current server collection. Each row keeps a reference to the entry,
     * and the status {@link Label} is re-read from the (volatile) entry status every frame in {@link #render}
     * so background ping completions show up without extra plumbing.
     */
    private void rebuildRows() {
        listTable.clearChildren();
        if (servers.list().isEmpty()) {
            Label empty = new Label("No servers yet. Add one to get started.", skin, "muted");
            listTable.add(empty).pad(24f).row();
            return;
        }
        for (ServerEntry e : servers.list()) {
            listTable.add(buildRow(e)).expandX().fillX().pad(4f).row();
        }
    }

    private Table buildRow(ServerEntry entry) {
        Label name = new Label(entry.name, skin);
        name.setAlignment(Align.left);

        Label addr = new Label(entry.address(), skin, "muted");

        Label status = new Label("", skin);
        status.setName("status");

        TextButton join = new TextButton("Join", skin);
        TextButton del  = new TextButton("Delete", skin);

        join.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                joinServer(entry);
            }
        });
        del.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                servers.remove(entry);
                rebuildRows();
            }
        });

        Table row = new Table(skin);
        row.setBackground("d_row");
        row.pad(8f);
        row.add(name).width(150f).padLeft(8f);
        row.add(addr).expandX().fillX().padLeft(12f);
        row.add(status).width(100f).padLeft(12f);
        row.add(join).width(80f).height(36f).padLeft(8f);
        row.add(del).width(80f).height(36f).padLeft(8f);

        // Stash the entry on the row so render() can refresh status text.
        row.setUserObject(entry);
        return row;
    }

    /**
     * Connects a fresh client to the remote dedicated server and pushes {@link GameScreen}. No integrated server
     * is started for multiplayer.
     */
    private void joinServer(ServerEntry entry) {
        Sessions.Session session;
        try {
            session = Sessions.multiplayer(entry.host, entry.port);
        } catch (Exception ex) {
            Gdx.app.log("Multiplayer", "Failed to connect to " + entry.address() + ": " + ex.getMessage());
            return;
        }
        app.setScreen(new GameScreen(app, entry.name, session.level(), session.connection(), session.integrated()));
    }

    private void refreshStatusLabels() {
        for (com.badlogic.gdx.scenes.scene2d.Actor child : listTable.getChildren()) {
            if (!(child instanceof Table row)) continue;
            Object obj = row.getUserObject();
            if (!(obj instanceof ServerEntry entry)) continue;
            com.badlogic.gdx.scenes.scene2d.Actor statusActor = row.findActor("status");
            if (!(statusActor instanceof Label status)) continue;
            applyStatus(status, entry.status);
        }
    }

    private void applyStatus(Label label, ServerEntry.Status status) {
        switch (status) {
            case ACTIVE:
                label.setText("ACTIVE");
                label.setStyle(skin.get("ok", Label.LabelStyle.class));
                break;
            case INACTIVE:
                label.setText("INACTIVE");
                label.setStyle(skin.get("bad", Label.LabelStyle.class));
                break;
            case CHECKING:
                label.setText("checking...");
                label.setStyle(skin.get("pending", Label.LabelStyle.class));
                break;
            case UNKNOWN:
            default:
                label.setText("unknown");
                label.setStyle(skin.get("muted", Label.LabelStyle.class));
                break;
        }
    }

    private void showAddDialog() {
        TextField nameField = new TextField("", skin);
        nameField.setMessageText("Friendly name");
        TextField hostField = new TextField("", skin);
        hostField.setMessageText("Host or IP");
        TextField portField = new TextField("25565", skin);
        portField.setTextFieldFilter(new TextField.TextFieldFilter.DigitsOnlyFilter());

        Dialog dialog = new Dialog("Add Server", skin) {
            @Override
            protected void result(Object obj) {
                if (!Boolean.TRUE.equals(obj)) return;
                int port;
                try {
                    port = Integer.parseInt(portField.getText().trim());
                } catch (NumberFormatException ex) {
                    Gdx.app.log("Multiplayer", "Invalid port: " + portField.getText());
                    return;
                }
                if (servers.add(nameField.getText(), hostField.getText(), port)) {
                    rebuildRows();
                    servers.pingAsync(servers.list().get(servers.list().size() - 1));
                } else {
                    Gdx.app.log("Multiplayer", "Failed to add server (validation)");
                }
            }
        };
        dialog.getContentTable().pad(12f).defaults().pad(6f);
        dialog.getContentTable().add(new Label("Name:", skin));
        dialog.getContentTable().add(nameField).width(280f).row();
        dialog.getContentTable().add(new Label("Host:", skin));
        dialog.getContentTable().add(hostField).width(280f).row();
        dialog.getContentTable().add(new Label("Port:", skin));
        dialog.getContentTable().add(portField).width(120f).row();
        dialog.button("Add", Boolean.TRUE);
        dialog.button("Cancel", Boolean.FALSE);
        dialog.key(13, Boolean.TRUE);
        dialog.key(131, Boolean.FALSE);
        dialog.show(stage);
        stage.setKeyboardFocus(nameField);
    }

    @Override public void show() {
        Gdx.input.setInputProcessor(stage);
        rebuildRows();
        servers.pingAll();
    }

    @Override public void render(float dt) {
        Gdx.gl.glClearColor(0.08f, 0.09f, 0.11f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        refreshStatusLabels();
        stage.act(dt);
        stage.draw();
    }

    @Override public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
    }

    @Override public void dispose() {
        stage.dispose();
    }
}
