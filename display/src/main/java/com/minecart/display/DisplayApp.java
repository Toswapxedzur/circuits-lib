package com.minecart.display;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.minecart.display.log.Slf4jApplicationLogger;
import com.minecart.display.screen.MainMenuScreen;
import com.minecart.display.screen.SnapScreen;
import com.minecart.display.server.ServerManager;
import com.minecart.display.session.Sessions;
import com.minecart.display.ui.Skins;
import com.minecart.display.world.WorldEntry;
import com.minecart.display.world.WorldManager;
import com.minecart.foundation.GameMode;

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
        // Re-route libGDX's own log/error/debug calls (and anything else inside libGDX that uses
        // Gdx.app.log such as AssetManager) through SLF4J so everything ends up in the same console
        // pattern and session log file. Must run before any other Gdx.app.* logging fires.
        Gdx.app.setApplicationLogger(new Slf4jApplicationLogger());
        skin = Skins.build();
        worlds = new WorldManager();
        servers = new ServerManager();
        setScreen(new MainMenuScreen(this));
        devAutoJoin();
    }

    /** Dev harness — no-op unless {@code -Dsnap.autojoin=<worldName>} is set. Boots straight into that world's
     *  3D {@link SnapScreen}, bypassing the menus, so the engine-rendered board can be screenshot-verified
     *  without driving the GUI (which can't be automated headlessly here). Never fires in normal runs. */
    private void devAutoJoin() {
        // DEV: -Dsnap.gallery=1 boots straight into the debug model gallery (no world needed), for screenshots.
        if ("1".equals(System.getProperty("snap.gallery"))) {
            setScreen(new com.minecart.display.screen.ModelGalleryScreen(this));
            return;
        }
        String name = System.getProperty("snap.autojoin");
        if (name == null) {
            return;
        }
        for (WorldEntry w : worlds.list()) {
            if (w.name().equals(name) && w.mode() == GameMode.SNAP_3D) {
                try {
                    Sessions.Session s = Sessions.singleplayer(w);
                    setScreen(new SnapScreen(this, w.name(), s.level(), s.connection(), s.integrated()));
                } catch (Exception e) {
                    Gdx.app.error("DisplayApp", "snap.autojoin failed for '" + name + "'", e);
                }
                return;
            }
        }
        Gdx.app.error("DisplayApp", "snap.autojoin: no snap-mode world named '" + name + "'");
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

    /**
     * Swaps to {@code screen} and disposes the outgoing one. libGDX {@link Game#setScreen} only calls
     * {@code hide()} on the previous screen and never {@code dispose()}, so without this override every
     * navigation leaked the old screen's GPU/native resources (Stages, Textures, ShapeRenderers). We
     * dispose after the base swap has run {@code hide()} on the old screen and {@code show()} on the new
     * one, so a screen's {@code dispose()} always sees a clean, already-hidden state.
     */
    @Override
    public void setScreen(Screen screen) {
        Screen previous = getScreen();
        super.setScreen(screen);
        if (previous != null && previous != screen) {
            previous.dispose();
        }
    }

    @Override
    public void dispose() {
        // Game.dispose() calls the current screen's hide() but NOT dispose(), so the active screen's
        // GPU/native resources would leak when the OS window closes (e.g. while in-world). Let super run
        // hide() first, then dispose the (now-hidden) current screen. Screen.dispose is guarded to be
        // idempotent, so this never double-frees a screen already disposed via setScreen().
        super.dispose();
        Screen current = getScreen();
        if (current != null) {
            current.dispose();
        }
        if (servers != null) servers.shutdown();
        if (skin != null) skin.dispose();
    }
}
