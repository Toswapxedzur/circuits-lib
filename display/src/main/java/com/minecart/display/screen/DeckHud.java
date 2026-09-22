package com.minecart.display.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.minecart.display.render.engine.PhysicalBoardView;
import com.minecart.display.snap.PhysicalEditor;
import com.minecart.display.snap.SnapModelBridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The 3D "poker-hand" deck HUD — draws the held inventory as a fanned hand of cards on top of the world, plus the
 * E-panel catalog picker when it is open. Pure presentation over the board's {@link PhysicalBoardView#drawCard card
 * renderer} and the {@link PhysicalEditor}'s deck state; split out of {@link SnapScreen} (SRP) so the screen keeps
 * lifecycle + input. The screen owns the input-driven picker state ({@code pickerOpen} / {@code pickerIndex}) and
 * passes it into {@link #draw}; this class owns only the eased fan animation + the HUD cameras.
 */
final class DeckHud {
    /** Eased fan state: the centre + lift angles GLIDE toward their targets each frame so selection and the raised
     *  card animate smoothly instead of snapping. {@code init} snaps on the first frame (or when a panel reopens). */
    static final class FanAnim { float center, raise, target; boolean init; }

    private final PhysicalBoardView board;
    private final PhysicalEditor editor;

    private PerspectiveCamera deckCam;   // HUD camera for the hand fan
    private ShapeRenderer deckDim;       // dim backdrop behind the open picker
    final FanAnim deckAnim = new FanAnim();   // eased state for the hand fan (smooth ←/→)
    final FanAnim pickerAnim = new FanAnim(); // eased state for the E-panel fan

    DeckHud(PhysicalBoardView board, PhysicalEditor editor) {
        this.board = board;
        this.editor = editor;
    }

    /** Snaps the picker fan on its next draw (called when the E-panel (re)opens). */
    void resetPickerAnim() {
        pickerAnim.init = false;
    }

    /** The full pickable catalog (every registered component, skipping the empty Cursor which is always in-hand). */
    List<String> pickerIds() {
        List<String> out = new ArrayList<>();
        for (SnapModelBridge.Comp c : SnapModelBridge.CATALOG) {
            if (!c.modelId().isEmpty()) out.add(c.modelId());
        }
        return out;
    }

    /** The catalog id the open picker is highlighting (clamped to {@code pickerIndex}). */
    String currentPick(int pickerIndex) {
        List<String> cat = pickerIds();
        if (cat.isEmpty()) return "";
        return cat.get(Math.max(0, Math.min(pickerIndex, cat.size() - 1)));
    }

    void draw(boolean pickerOpen, int pickerIndex, float dt) {
        if (board == null || editor == null) {
            return;
        }
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        if (deckCam == null) {
            deckCam = new PerspectiveCamera(50f, w, h);
        }
        deckCam.viewportWidth = w; deckCam.viewportHeight = h;
        deckCam.position.set(0f, 0f, 64f);
        deckCam.up.set(0f, 1f, 0f);
        deckCam.lookAt(0f, 0f, 0f);
        deckCam.near = 0.5f; deckCam.far = 800f; deckCam.update();
        Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT); // the HUD fan draws ON TOP of the world
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < editor.deckSize(); i++) ids.add(editor.deckCard(i));
        // pivot near the bottom; cards face you and ROLL about the view axis into a poker-hand fan (selected upright,
        // centered, raised + floated forward). Args: pivotY, arm, deg-per-width(spread), cardSize, tilt, selRaise, selFwd, selScale.
        int sel = editor.deckSelected();
        // deck: selected is centered AND raised. Pivot low enough that cards hug the BOTTOM edge of the screen
        // (viewport half-height at the card depth ≈ 26–30 world units), like a hand of cards held at the table rim.
        // tilt ~60° to the FRONT (top edge toward the viewer — owner-set); -Dsnap.decktilt overrides for tuning shots
        // Owner measures tilt FROM THE TABLE: cards stand 75-80° from horizontal = -15 here (this param is degrees
        // leaned forward from upright; owner picked -15 from a labeled variant strip). -Dsnap.decktilt overrides.
        float deckTilt = Float.parseFloat(System.getProperty("snap.decktilt", "-45"));
        // Fan circle radius 65 (owner: 3-4× the original 18). Pivot -93 SUBMERGES 35-45% of each card below the
        // bottom screen edge (owner value); spacing 3°/width = arc step ≈ 6.8 — a tiny gap, never overlapping.
        // Owner: "curve less, size -30%". Radius 130 (2×) with the angular step halved keeps the linear spacing but
        // FLATTENS the arc; cardSize 20 (= 28.6 × 0.7). Pivot -149 keeps the card centres at the same height so
        // ~90% of the centre card clears the bottom edge. -Dsnap.deckpivot overrides for tuning.
        float deckPivot = Float.parseFloat(System.getProperty("snap.deckpivot", "-155"));
        drawFan(deckCam, ids, sel, sel, deckAnim, dt, deckPivot, 130f, 3f, 20f, deckTilt, 6f, 8f, 1.3f);
        if (pickerOpen) drawPicker(w, h, pickerIndex, dt);
    }

    /** The E-panel: dim the world, then draw the full catalog as a big centered fan with {@code pickerIndex} raised. */
    private void drawPicker(int w, int h, int pickerIndex, float dt) {
        if (deckDim == null) deckDim = new ShapeRenderer();
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        deckDim.getProjectionMatrix().setToOrtho2D(0f, 0f, w, h);
        deckDim.begin(ShapeRenderer.ShapeType.Filled);
        deckDim.setColor(0f, 0f, 0f, 0.62f);
        deckDim.rect(0f, 0f, w, h);
        deckDim.end();
        Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        deckCam.position.set(0f, 6f, 96f); // re-aim the HUD camera to a centered, straight-on view for the picker
        deckCam.lookAt(0f, 6f, 0f);
        deckCam.update();
        // picker: the strip stays centered on the MIDDLE (symmetric); the highlighted card is raised where it sits.
        drawFan(deckCam, pickerIds(), pickerIds().size() / 2, pickerIndex, pickerAnim, dt, -34f, 34f, 2.5f, 9f, 6f, 5f, 14f, 1.45f);
    }

    /** Lays {@code ids} out as a poker fan (cumulative width → slot angle, so cards never overlap) and draws each
     *  as a 3D card via {@link PhysicalBoardView#drawCard}. {@code sel} is centered + raised. Far cards draw first
     *  so the selected one lands on top. */
    private void drawFan(Camera cam, List<String> ids, int centerIdx, int raiseIdx,
                         FanAnim anim, float dt,
                         float pivotY, float armLen, float degPerWidth, float cardSize, float tiltDeg,
                         float selRaise, float selForward, float selScale) {
        int n = ids.size();
        if (n == 0) return;
        centerIdx = Math.max(0, Math.min(centerIdx, n - 1));
        raiseIdx = Math.max(0, Math.min(raiseIdx, n - 1));
        float[] wdt = new float[n];
        for (int i = 0; i < n; i++) wdt[i] = SnapModelBridge.deckWidth(ids.get(i));
        // ABSOLUTE cumulative roll of each card (independent of selection): index 0 at 0, spaced by mean width.
        float[] abs = new float[n];
        for (int i = 1; i < n; i++) abs[i] = abs[i - 1] + (wdt[i - 1] + wdt[i]) / 2f * degPerWidth;
        // Glide the fan's CENTRE (which card sits at roll 0) and the LIFT position toward their targets — this is the
        // whole animation: ←/→ retargets, the fan eases over. Frame-rate-independent exponential ease; snap on init.
        float targetC = abs[centerIdx], targetR = abs[raiseIdx];
        anim.target = targetC;
        if (!anim.init) { anim.center = targetC; anim.raise = targetR; anim.init = true; }
        else {
            float k = 1f - (float) Math.exp(-dt * 14f);
            anim.center += (targetC - anim.center) * k;
            anim.raise += (targetR - anim.raise) * k;
        }
        float raiseSpan = degPerWidth * 2.5f; // angular reach of the lift bump — ~one card step, so it slides cleanly
        final float[] roll = new float[n], lift = new float[n];
        for (int i = 0; i < n; i++) {
            roll[i] = abs[i] - anim.center;
            float t = Math.max(0f, 1f - Math.abs(abs[i] - anim.raise) / raiseSpan);
            lift[i] = t * t * (3f - 2f * t); // smoothstep 0..1: 1 at the lift centre, easing to 0 a step away
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        // draw least-lifted (and farthest) first so the raised card lands on top
        Arrays.sort(order, (a, b) -> lift[a] != lift[b]
                ? Float.compare(lift[a], lift[b]) : Float.compare(Math.abs(roll[b]), Math.abs(roll[a])));
        for (int idx : order) {
            String id = ids.get(idx);
            if (id == null || id.isEmpty()) continue; // Cursor card: no model
            board.drawCard(cam, id, cardPose(id, roll[idx], lift[idx], pivotY, armLen,
                    cardSize, tiltDeg, selRaise, selForward, selScale));
        }
    }

    /** The world transform for one fan card — a POKER-HAND spread: the card faces the camera (top face toward the
     *  screen, long axis vertical), and the "fan" is a small ROLL about the view axis around a shared pivot at the
     *  bottom, so the cards splay and overlap like a held hand of cards. Only a slight backward tilt for depth.
     *  Selected card sits upright at center (roll≈0), raised + floated toward the camera + a touch bigger. */
    private Matrix4 cardPose(String id, float rollDeg, float lift, float pivotY,
            float armLen, float cardSize, float tiltDeg, float selRaise, float selForward, float selScale) {
        float[] e = board.modelExtent(id);
        float cx = (e[0] + e[3]) / 2f, cy = (e[1] + e[4]) / 2f, cz = (e[2] + e[5]) / 2f;
        float span = Math.max(Math.max(e[3] - e[0], e[4] - e[1]), e[5] - e[2]);
        float s = cardSize / Math.max(1f, span);
        s *= 1f + (selScale - 1f) * lift;                       // grow smoothly toward the selected size (lift 0..1)
        Matrix4 m = new Matrix4();
        m.translate(0f, pivotY, selForward * lift);             // shared fan pivot (bottom); floats forward with lift
        m.rotate(0f, 0f, 1f, rollDeg);                          // the POKER SPREAD: roll about the view axis
        m.translate(0f, armLen + selRaise * lift, 0f);          // out from the pivot to this card's centre
        m.rotate(1f, 0f, 0f, tiltDeg);                          // slight backward tilt for a held-card depth cue
        // Face the camera with the long axis VERTICAL: model long X → up(+Y), top +Y → camera(+Z), short Z → right.
        m.rotate(0f, 0f, 1f, 90f);
        m.rotate(1f, 0f, 0f, 90f);
        m.rotate(0f, 1f, 0f, SnapModelBridge.holdAngle(id)); // authored per-card roll
        m.scale(s, s, s);
        m.translate(-cx, -cy, -cz);                             // center the art at the slot
        return m;
    }
}
