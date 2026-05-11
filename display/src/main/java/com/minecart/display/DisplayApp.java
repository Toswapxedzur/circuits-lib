package com.minecart.display;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.minecart.display.screen.MainMenuScreen;
import com.minecart.display.server.ServerManager;
import com.minecart.display.ui.Skins;
import com.minecart.display.world.WorldManager;

/**
 * Top-level LibGDX entry point. Owns the shared {@link Skin} and the singleplayer/multiplayer managers,
 * then hands off to {@link com.badlogic.gdx.Screen}s for actual UI. Each screen reads everything it needs
 * via getters on this app.
 */
public class DisplayApp extends Game {

    private Skin skin;
    private WorldManager worlds;
    private ServerManager servers;

    @Override
    public void create() {
        skin = Skins.build();
        worlds = new WorldManager();
        servers = new ServerManager();
        setScreen(new MainMenuScreen(this));
    }

    public Skin getSkin() {
        return skin;
    }

    public WorldManager getWorlds() {
        return worlds;
    }

    public ServerManager getServers() {
        return servers;
    }

    @Override
    public void dispose() {
        super.dispose();
        if (servers != null) servers.shutdown();
        if (skin != null) skin.dispose();
    }
}
