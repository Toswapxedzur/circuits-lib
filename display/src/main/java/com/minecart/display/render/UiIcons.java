package com.minecart.display.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Disposable;

/**
 * Tiny {@link Texture} factory for chrome icons that the default LibGDX bitmap font can't render
 * (notably the {@code U+1F5D1} trash-can glyph the world dropdown previously used). Each icon is built
 * once on demand from a {@link Pixmap} so we don't need to ship PNG resource files for trivial UI
 * decorations.
 *
 * <p>Lifecycle: a single instance lives for the duration of a {@link com.minecart.display.screen.GameScreen};
 * the screen calls {@link #dispose()} to release the GPU textures alongside its own {@link Textures}.
 */
public final class UiIcons implements Disposable {

    private Texture trash;
    private Texture cursor;

    /** Returns (and lazily builds) a 24×24 light-grey trash-can icon on a transparent background. */
    public Texture trash() {
        if (trash == null) {
            trash = buildTrash();
        }
        return trash;
    }

    /**
     * Returns (and lazily builds) a 24×24 light-grey arrow-cursor icon on a transparent background.
     * Used by the "deselect tool" tile in the palette so users can drop a placement tool and switch
     * back to plain hover / drag / pan interactions without hunting for the Escape key.
     */
    public Texture cursor() {
        if (cursor == null) {
            cursor = buildCursor();
        }
        return cursor;
    }

    private static Texture buildTrash() {
        int w = 24, h = 24;
        Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        try {
            pm.setColor(0f, 0f, 0f, 0f);
            pm.fill();

            Color stroke = new Color(0.92f, 0.92f, 0.95f, 1f);
            pm.setColor(stroke);

            // Handle: small bar on top of the lid.
            pm.fillRectangle(9, 3, 6, 2);
            // Lid: full-width horizontal bar.
            pm.fillRectangle(3, 6, 18, 2);
            // Body outline.
            pm.fillRectangle(5, 9, 14, 1);   // top edge of body (under lid)
            pm.fillRectangle(5, 21, 14, 1);  // bottom edge
            pm.fillRectangle(5, 9, 1, 13);   // left wall
            pm.fillRectangle(18, 9, 1, 13);  // right wall
            // Vertical "ribs" inside the can to read as a trash icon at small sizes.
            pm.fillRectangle(9, 11, 1, 9);
            pm.fillRectangle(12, 11, 1, 9);
            pm.fillRectangle(15, 11, 1, 9);

            return new Texture(pm);
        } finally {
            pm.dispose();
        }
    }

    /**
     * Builds a classic top-left arrow cursor pointing toward the lower-right. Drawn as a filled
     * silhouette outlined by the same light grey as the trash icon so the two read as a matched
     * set in the palette / chrome.
     */
    private static Texture buildCursor() {
        int w = 24, h = 24;
        Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        try {
            pm.setColor(0f, 0f, 0f, 0f);
            pm.fill();

            Color fill = new Color(0.92f, 0.92f, 0.95f, 1f);
            pm.setColor(fill);

            // Arrow head: a right-triangle with the right-angle at the top-left, hypotenuse running
            // from (3, 3) down to (12, 12). Filled by drawing successively shorter horizontal rows.
            // Row r (0-based from top) is filled from column 3 to column (3 + r). Stops at r = 11 so
            // the head sits roughly in the upper-left 12x12 quadrant.
            for (int r = 0; r <= 11; r++) {
                int y = 3 + r;
                pm.fillRectangle(3, y, r + 1, 1);
            }

            // Tail: an angled rectangle from the bottom-right of the head down to (15, 21). Approximated
            // as two stacked 3-wide strokes offset diagonally so the tail visually points away from the
            // arrow head's hypotenuse rather than running straight down.
            for (int i = 0; i < 8; i++) {
                int x = 8 + i;
                int y = 13 + i;
                pm.fillRectangle(x, y, 3, 1);
            }

            return new Texture(pm);
        } finally {
            pm.dispose();
        }
    }

    @Override
    public void dispose() {
        if (trash != null) {
            trash.dispose();
            trash = null;
        }
        if (cursor != null) {
            cursor.dispose();
            cursor = null;
        }
    }
}
