package com.minecart.display.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.List;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;

/**
 * Builds the shared {@link Skin} used by every {@link com.badlogic.gdx.Screen} in this app.
 *
 * <p>All drawables are tinted slices of a single 1x1 white pixel — no asset files needed yet.
 * Drop in a real {@code uiskin.json} later by replacing this class's call site in
 * {@link com.minecart.display.DisplayApp}.
 */
public final class Skins {

    private Skins() {}

    public static Skin build() {
        Skin skin = new Skin();

        Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pm.setColor(Color.WHITE);
        pm.fill();
        skin.add("white", new Texture(pm));
        pm.dispose();

        addDrawable(skin, "d_button",    new Color(0.20f, 0.22f, 0.28f, 1f));
        addDrawable(skin, "d_button_h",  new Color(0.30f, 0.34f, 0.42f, 1f));
        addDrawable(skin, "d_button_d",  new Color(0.10f, 0.12f, 0.18f, 1f));
        addDrawable(skin, "d_field",     new Color(0.06f, 0.07f, 0.10f, 1f));
        addDrawable(skin, "d_cursor",    Color.WHITE);
        addDrawable(skin, "d_selection", new Color(0.30f, 0.50f, 0.80f, 0.50f));
        addDrawable(skin, "d_panel",     new Color(0.14f, 0.16f, 0.22f, 1f));
        addDrawable(skin, "d_window",    new Color(0.10f, 0.12f, 0.16f, 0.97f));
        addDrawable(skin, "d_list_sel",  new Color(0.30f, 0.50f, 0.80f, 0.70f));
        addDrawable(skin, "d_scroll",    new Color(0.30f, 0.34f, 0.42f, 1f));
        addDrawable(skin, "d_row",       new Color(0.16f, 0.18f, 0.24f, 1f));
        // Slider track is a thin pill-like strip; keep it lighter than the field background so the knob
        // contrasts cleanly against it without needing dedicated artwork.
        addDrawable(skin, "d_slider_bg", new Color(0.22f, 0.26f, 0.34f, 1f));
        // Checkbox swatches: an empty box vs. a filled box. Using the same tints as button up/down so
        // the visual language stays consistent across the panel — no fancy tick artwork yet.
        addDrawable(skin, "d_check_off", new Color(0.20f, 0.22f, 0.28f, 1f));
        addDrawable(skin, "d_check_on",  new Color(0.30f, 0.55f, 0.85f, 1f));
        skin.getDrawable("d_check_off").setMinWidth(22f);
        skin.getDrawable("d_check_off").setMinHeight(22f);
        skin.getDrawable("d_check_on").setMinWidth(22f);
        skin.getDrawable("d_check_on").setMinHeight(22f);

        BitmapFont font = new BitmapFont();
        skin.add("default-font", font);

        TextButton.TextButtonStyle btn = new TextButton.TextButtonStyle();
        btn.up   = skin.getDrawable("d_button");
        btn.over = skin.getDrawable("d_button_h");
        btn.down = skin.getDrawable("d_button_d");
        btn.font = font;
        btn.fontColor = Color.WHITE;
        skin.add("default", btn);

        Label.LabelStyle lbl = new Label.LabelStyle(font, Color.WHITE);
        skin.add("default", lbl);

        Label.LabelStyle muted = new Label.LabelStyle(font, new Color(0.65f, 0.68f, 0.74f, 1f));
        skin.add("muted", muted);

        Label.LabelStyle ok = new Label.LabelStyle(font, new Color(0.40f, 0.85f, 0.45f, 1f));
        skin.add("ok", ok);

        Label.LabelStyle bad = new Label.LabelStyle(font, new Color(0.90f, 0.45f, 0.45f, 1f));
        skin.add("bad", bad);

        Label.LabelStyle pending = new Label.LabelStyle(font, new Color(0.85f, 0.80f, 0.40f, 1f));
        skin.add("pending", pending);

        TextField.TextFieldStyle tf = new TextField.TextFieldStyle();
        tf.font = font;
        tf.fontColor = Color.WHITE;
        tf.background = skin.getDrawable("d_field");
        tf.cursor = skin.getDrawable("d_cursor");
        tf.selection = skin.getDrawable("d_selection");
        skin.add("default", tf);

        ScrollPane.ScrollPaneStyle sp = new ScrollPane.ScrollPaneStyle();
        sp.background = skin.getDrawable("d_panel");
        sp.vScroll = skin.getDrawable("d_field");
        sp.vScrollKnob = skin.getDrawable("d_scroll");
        skin.add("default", sp);

        List.ListStyle lst = new List.ListStyle();
        lst.font = font;
        lst.fontColorSelected = Color.WHITE;
        lst.fontColorUnselected = Color.LIGHT_GRAY;
        lst.selection = skin.getDrawable("d_list_sel");
        skin.add("default", lst);

        Window.WindowStyle win = new Window.WindowStyle();
        win.background = skin.getDrawable("d_window");
        win.titleFont = font;
        win.titleFontColor = Color.WHITE;
        skin.add("default", win);

        // Slider: reuse the field strip as the background and the standard button drawables as the knob so
        // hover / press feedback comes for free without dedicated artwork.
        Slider.SliderStyle slider = new Slider.SliderStyle();
        slider.background = skin.getDrawable("d_slider_bg");
        slider.knob = skin.getDrawable("d_button");
        slider.knobOver = skin.getDrawable("d_button_h");
        slider.knobDown = skin.getDrawable("d_button_d");
        skin.add("default-horizontal", slider);

        // CheckBox: only checkboxOn/checkboxOff are strictly required (CheckBoxStyle extends
        // TextButtonStyle, and we don't render an Up background behind the swatch). Provide a font
        // + fontColor so the label next to the box renders, and reuse the default button up so the
        // CheckBox isn't entirely empty if a future caller asks for a background.
        CheckBox.CheckBoxStyle cb = new CheckBox.CheckBoxStyle();
        cb.checkboxOff = skin.getDrawable("d_check_off");
        cb.checkboxOn  = skin.getDrawable("d_check_on");
        cb.font = font;
        cb.fontColor = Color.WHITE;
        skin.add("default", cb);

        // SelectBox: dropdown's background pill, the popup list, and the popup scroll wrapper.
        // The popup uses the existing default ScrollPane + List styles we built above, plumbed in
        // explicitly because SelectBoxStyle doesn't inherit them.
        SelectBox.SelectBoxStyle sel = new SelectBox.SelectBoxStyle();
        sel.font = font;
        sel.fontColor = Color.WHITE;
        sel.background = skin.getDrawable("d_field");
        sel.scrollStyle = sp;
        sel.listStyle = lst;
        skin.add("default", sel);

        return skin;
    }

    /**
     * {@link Skin#add(String, Object)} keys by {@code obj.getClass()}, but {@link Skin#getDrawable(String)}
     * only checks the {@link Drawable} slot. Use the 3-arg form to register tinted drawables under the
     * abstract {@link Drawable} key so name lookups resolve.
     */
    private static void addDrawable(Skin skin, String name, Color tint) {
        skin.add(name, skin.newDrawable("white", tint), Drawable.class);
    }
}
