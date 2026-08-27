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
        for (ComponentModel b : parts.bases) boxes.addAll(b.staticBoxes);            // blank base, every colour
        for (ComponentModel cap : parts.capacitorSizes) boxes.addAll(cap.staticBoxes); // the 3 sizes
        for (ComponentModel sw : parts.switches) boxes.addAll(sw.staticBoxes);
        for (ComponentModel ps : parts.pressSwitches) boxes.addAll(ps.staticBoxes);
        for (ComponentModel r : parts.resistors) boxes.addAll(r.staticBoxes);
        for (ComponentModel l : parts.leds) boxes.addAll(l.staticBoxes);
        boxes.addAll(parts.slider.boxes());
        boxes.addAll(parts.button.boxes());
        boxes.add(EngineDemoApp.boardBox());

        FileHandle dir = Gdx.files.local(OUT_DIR);
        dir.mkdirs();
        Gdx.app.log("seed", "writing sprites to " + dir.file().getAbsolutePath());
        Set<String> seen = new LinkedHashSet<>();
        int count = 0;
        for (PaletteDither.Face f : PaletteDither.faces(boxes)) {
            String name = PaletteDither.faceName(f.box(), f.faceId());
            if (!seen.add(name)) {
                continue; // identical object-space face already drawn (shared across instances)
            }
            Pixmap pm = PaletteDither.drawFace(f.box(), f.faceId());
            PixmapIO.writePNG(dir.child(name + ".png"), pm);
            pm.dispose();
            count++;
        }
        Gdx.app.log("seed", "done: " + count + " sprites");
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
