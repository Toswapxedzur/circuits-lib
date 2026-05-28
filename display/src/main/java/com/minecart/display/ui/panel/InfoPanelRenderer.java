package com.minecart.display.ui.panel;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Array;
import com.minecart.ui.panel.InfoPanelSchema;
import com.minecart.ui.panel.PanelField;
import com.minecart.ui.panel.PanelSnapshot;
import com.minecart.ui.panel.fields.CheckboxSpec;
import com.minecart.ui.panel.fields.DropdownSpec;
import com.minecart.ui.panel.fields.LabelSpec;
import com.minecart.ui.panel.fields.NumberFieldSpec;
import com.minecart.ui.panel.fields.SliderSpec;
import com.minecart.ui.panel.fields.TextFieldSpec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Renders an {@link InfoPanelSchema} as a Scene2D {@link Dialog} and reports the user's edits to
 * a caller-supplied {@link Consumer} when Save is clicked. The dialog is non-modal: it's
 * displayed on the editor's UI stage with {@code dialog.setModal(false)} so the user can keep
 * panning/dragging the world while the panel is open.
 *
 * <p>This class owns no state across calls; the call-site (typically {@code GameScreen}) is
 * responsible for keeping a reference to the open dialog and closing it before opening a new one
 * — the "one panel at a time" rule from the design. See {@link InfoPanelController} for that glue.
 */
public final class InfoPanelRenderer {

    private InfoPanelRenderer() {}

    /**
     * Builds and shows a dialog for {@code schema}. The dialog auto-hides on Save (after invoking
     * {@code onSave}) or Cancel (without). Returns the {@link Dialog} so the caller can
     * {@code .hide()} it programmatically (used to enforce the single-panel invariant when the
     * user clicks a different element while a panel is already open).
     */
    public static Dialog open(Stage stage, Skin skin, InfoPanelSchema schema, Consumer<PanelSnapshot> onSave) {
        final Map<String, Actor> editors = new LinkedHashMap<>();

        Dialog dialog = new Dialog(schema.getTitle(), skin);
        // Non-modal so the user can still pan/drag the world while the panel sits on top.
        dialog.setModal(false);
        dialog.setMovable(true);
        dialog.setResizable(false);

        Table content = dialog.getContentTable();
        content.pad(10f);
        content.defaults().pad(4f).left();

        for (PanelField field : schema.getFields()) {
            content.add(new Label(field.getLabel(), skin)).left();
            Actor editor = buildEditor(field, skin);
            editors.put(field.getKey(), editor);
            content.add(editor).width(220f).row();
        }

        TextButton saveBtn = new TextButton("Save", skin);
        TextButton cancelBtn = new TextButton("Cancel", skin);
        saveBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                PanelSnapshot snap = collect(schema, editors);
                dialog.hide();
                onSave.accept(snap);
            }
        });
        cancelBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                dialog.hide();
            }
        });
        Table buttons = dialog.getButtonTable();
        buttons.pad(8f);
        buttons.add(saveBtn).width(100f).height(36f).padRight(8f);
        buttons.add(cancelBtn).width(100f).height(36f);

        dialog.show(stage);
        return dialog;
    }

    private static Actor buildEditor(PanelField field, Skin skin) {
        if (field instanceof LabelSpec ls) {
            return new Label(ls.getValue(), skin, "muted");
        }
        if (field instanceof TextFieldSpec tfs) {
            TextField tf = new TextField(tfs.getInitialValue(), skin);
            return tf;
        }
        if (field instanceof NumberFieldSpec nfs) {
            TextField tf = new TextField(formatDouble(nfs.getInitialValue()), skin);
            // Allow digits, sign, decimal point, and scientific-notation exponent. Anything outside
            // that set is rejected at keypress time so the user can't even type garbage; the actual
            // numeric parse happens in collect() so we still cope if a paste sneaks something past.
            tf.setTextFieldFilter((textField, c) ->
                    Character.isDigit(c) || c == '-' || c == '.' || c == 'e' || c == 'E' || c == '+');
            return tf;
        }
        if (field instanceof CheckboxSpec cbs) {
            CheckBox cb = new CheckBox("", skin);
            cb.setChecked(cbs.getInitialValue());
            return cb;
        }
        if (field instanceof DropdownSpec dds) {
            SelectBox<String> sb = new SelectBox<>(skin);
            Array<String> items = new Array<>();
            for (String opt : dds.getOptions()) {
                items.add(opt);
            }
            sb.setItems(items);
            sb.setSelected(dds.getInitialValue());
            return sb;
        }
        if (field instanceof SliderSpec ss) {
            Slider sl = new Slider((float) ss.getMin(), (float) ss.getMax(), (float) ss.getStep(), false, skin);
            sl.setValue((float) ss.getInitialValue());
            return sl;
        }
        // Defensive default: render a disabled placeholder so the user still sees the row even
        // though we don't know how to edit it. Better than throwing and crashing the click handler.
        TextField placeholder = new TextField("[unsupported field type]", skin);
        placeholder.setDisabled(true);
        return placeholder;
    }

    private static PanelSnapshot collect(InfoPanelSchema schema, Map<String, Actor> editors) {
        PanelSnapshot.Builder b = PanelSnapshot.builder();
        for (PanelField field : schema.getFields()) {
            // Label rows produce no snapshot entry — they're read-only by construction.
            if (field instanceof LabelSpec) {
                continue;
            }
            Actor a = editors.get(field.getKey());
            if (a == null) {
                continue;
            }
            if (field instanceof TextFieldSpec) {
                b.put(field.getKey(), ((TextField) a).getText());
            } else if (field instanceof NumberFieldSpec nfs) {
                double parsed = parseDoubleOrInitial(((TextField) a).getText(), nfs.getInitialValue());
                b.put(field.getKey(), parsed);
            } else if (field instanceof CheckboxSpec) {
                b.put(field.getKey(), ((CheckBox) a).isChecked());
            } else if (field instanceof DropdownSpec) {
                @SuppressWarnings("unchecked")
                SelectBox<String> sb = (SelectBox<String>) a;
                String sel = sb.getSelected();
                if (sel != null) {
                    b.put(field.getKey(), sel);
                }
            } else if (field instanceof SliderSpec) {
                b.put(field.getKey(), (double) ((Slider) a).getValue());
            }
        }
        return b.build();
    }

    private static double parseDoubleOrInitial(String text, double fallback) {
        if (text == null) return fallback;
        String t = text.trim();
        if (t.isEmpty()) return fallback;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException ex) {
            // Send the initial value rather than NaN so a fat-fingered entry doesn't accidentally
            // become a "set to NaN" update on the server side. The user can reopen the panel and
            // see the unchanged value if the server didn't apply anything else.
            return fallback;
        }
    }

    private static String formatDouble(double v) {
        // Trim trailing zeros so a clean integer reads "1" rather than "1.0"; the underlying parse
        // still accepts either. We don't use String.format here because we want "1e-3" to round-trip.
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }
}
