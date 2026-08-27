package com.minecart.display.render.engine;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One-time seed generator: draws every part sprite procedurally and writes it as a fixed PNG under
 * {@code display/src/main/resources/textures/parts/}. Run once ({@code ./gradlew :display:seedtextures}),
 * commit the PNGs, then the runtime only ever LOADS them ({@link PartAtlas}) — the drawing here is the sole
 * procedural step, and from then on the atlas shows those exact texels (Minecraft-style, no runtime post).
 *
 * <p>It enumerates sprites from the same boxes the demo renders (both part models + the demo board), via the
 * same {@link PaletteDither#specs} the mesh baker uses, so the generated set matches what the atlas requests.
 */
public final class SeedPartTextures extends ApplicationAdapter {

    private static final String OUT_DIR = "src/main/resources/textures/parts"; // relative to the :display dir

    @Override
    public void create() {
        Parts parts = new Parts();
        List<PartMesh.Box> boxes = new ArrayList<>();
        boxes.addAll(parts.capacitor.staticBoxes);
        boxes.addAll(parts.slideSwitch.staticBoxes);
        boxes.addAll(parts.slider.boxes());
        boxes.add(EngineDemoApp.boardBox());

        Set<PaletteDither.Spec> specs = new LinkedHashSet<>(PaletteDither.specs(boxes));

        FileHandle dir = Gdx.files.local(OUT_DIR);
        dir.mkdirs();
        Gdx.app.log("seed", "writing " + specs.size() + " sprites to " + dir.file().getAbsolutePath());
        for (PaletteDither.Spec s : specs) {
            String name = PaletteDither.name(s);
            Pixmap pm = PaletteDither.drawTile(s);
            PixmapIO.writePNG(dir.child(name + ".png"), pm);
            pm.dispose();
            Gdx.app.log("seed", "  " + name + ".png (" + s.w() + "x" + s.h() + ")");
        }
        Gdx.app.log("seed", "done: " + specs.size() + " sprites");
        Gdx.app.exit();
    }

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Seed part textures");
        config.setInitialVisible(false);
        config.setWindowedMode(200, 100);
        new Lwjgl3Application(new SeedPartTextures(), config);
    }
}
