package com.minecart.display.screen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Input.Buttons;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.minecart.display.render.engine.PhysicalBoardView;
import com.minecart.display.render.engine.PhysicalBoardView.Focus;
import com.minecart.display.input.FreeCameraController;
import com.minecart.display.snap.InputScript;
import com.minecart.display.snap.PhysicalEditor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The physical free-placement editor's per-frame brain, split out of {@link SnapScreen} (SRP). It owns the
 * interactive editing state — what the crosshair is over ({@link #physFocus}), the sub-part being dragged
 * ({@link #grabbed}), the harness's synthetic LMB-held flag, and the focus-outline segment count — and runs the
 * once-per-frame {@link #update} (grab-follows-cursor, focus pick, momentary spring-back, placement ghost) plus the
 * Minecraft-style {@link #drawOutline focus outline}. The screen keeps the shared fly-camera capture
 * ({@code cursorCaught}/{@code fixedCam}, used by both board modes) and passes them in; a {@code rebuild} callback
 * re-solves the circuit when an interaction changes it. Verified via the scripted-input harness.
 */
final class SnapEditorController {
    private static final Logger log = LoggerFactory.getLogger(SnapEditorController.class);

    private final PhysicalBoardView physWorld;
    private final PhysicalEditor physEditor;
    private final FreeCameraController flyCam;
    private final PerspectiveCamera camera;
    private final DeckHud deckHud;
    private final Runnable rebuild;
    private final Consumer<Boolean> setCursorCaught; // the screen owns the shared fly-cam capture; we drive it
    private final BooleanSupplier isCursorCaught;
    private final BooleanSupplier isConnected;       // client↔server link alive? (net.connected probe)
    private final Consumer<Boolean> setFixedCam;     // the screen owns fixedCam (shared with grid mode)

    // Owned interactive state (read by the screen's render loop, status line and harness adapter).
    Focus physFocus;         // what the crosshair is over
    Focus grabbed;           // a sub-part being dragged (LMB held)
    boolean scriptLmbHeld;   // the harness's "LMB is held" (Gdx.input can't be faked)
    int outlineSegs;         // probe: segments drawn by the last focus outline
    boolean deckPicker;      // E-panel open: pick a component to add/replace; mouse-look off
    int pickerIndex;         // which catalog entry is highlighted in the open picker

    private ShapeRenderer outline; // Minecraft-style focus highlight (lazy)

    SnapEditorController(PhysicalBoardView physWorld, PhysicalEditor physEditor, FreeCameraController flyCam,
                         PerspectiveCamera camera, DeckHud deckHud, Runnable rebuild,
                         Consumer<Boolean> setCursorCaught, BooleanSupplier isCursorCaught,
                         BooleanSupplier isConnected, Consumer<Boolean> setFixedCam) {
        this.physWorld = physWorld;
        this.physEditor = physEditor;
        this.flyCam = flyCam;
        this.camera = camera;
        this.deckHud = deckHud;
        this.rebuild = rebuild;
        this.setCursorCaught = setCursorCaught;
        this.isCursorCaught = isCursorCaught;
        this.isConnected = isConnected;
        this.setFixedCam = setFixedCam;
    }

    /** The scripted-input harness / live-console adapter for this physical editor: synthetic events go through the
     *  SAME {@code onTouchDown/onKey/onScroll} the real input routes to, and probes expose the state the tests
     *  assert on. Held as a nested class so the controller's editing state is reached directly. */
    final InputScript.Host harness = new Harness();

    private final class Harness implements InputScript.Host {
        private int cx() { return Gdx.graphics.getWidth() / 2; }
        private int cy() { return Gdx.graphics.getHeight() / 2; }
        @Override public void keyDown(int keycode) { onKey(keycode); }
        @Override public void keyUp(int keycode) { /* EditInput has no keyUp behaviour */ }
        @Override public void mouse(int button, boolean down) {
            if (down) onTouchDown(button); else onTouchUp(button);
            if (button == Buttons.LEFT) scriptLmbHeld = down;
        }
        @Override public void scroll(float amountY) { onScroll(amountY); }
        @Override public void look(float dYawDeg, float dPitchDeg) { flyCam.look(dYawDeg, dPitchDeg); }
        @Override public String action(String verb, String[] a) {
            switch (verb) {
                case "cursor" -> { setCursorCaught.accept(a[0].equalsIgnoreCase("caught")); return null; }
                case "clear" -> { physWorld.clearAll(); rebuild.run(); return null; }
                case "deck" -> {
                    if (a[0].equals("add")) physEditor.deckAddRight(a[1]);
                    else if (a[0].equals("select")) physEditor.deckSetSelected(Integer.parseInt(a[1]));
                    else if (a[0].equals("clear")) { // back to the pristine hand: just the Cursor
                        while (physEditor.deckSize() > 1) physEditor.deckRemove();
                        physEditor.deckReplace("");
                        physEditor.deckSetSelected(0);
                    }
                    return "deck " + physEditor.deckSize() + " held=" + physEditor.modelId();
                }
                case "fixedcam" -> { boolean on = a[0].equalsIgnoreCase("on"); setFixedCam.accept(on); return "fixedcam " + on; }
                case "cam" -> { // cam <yaw> <pitch>: set the view angles directly (position unchanged)
                    flyCam.look(Float.parseFloat(a[0]) - flyCam.yawDeg(), Float.parseFloat(a[1]) - flyCam.pitchDeg());
                    return "cam yaw=" + flyCam.yawDeg() + " pitch=" + flyCam.pitchDeg();
                }
                case "aim" -> { // aim <placement> [sub]: point the crosshair at that hitbox's centre
                    int pi = Integer.parseInt(a[0]), sub = a.length > 1 ? Integer.parseInt(a[1]) : -1;
                    float[] b = physWorld.debugSubAabb(pi, sub);
                    Vector3 c = new Vector3((b[0] + b[3]) / 2f, (b[1] + b[4]) / 2f, (b[2] + b[5]) / 2f);
                    flyCam.lookAt(c);
                    return "aimed at " + c;
                }
                case "force" -> { // force <modelId> <x> <z> [yaw] [y]: place WITHOUT canPlace (test setups only)
                    float yaw = a.length > 3 ? Float.parseFloat(a[3]) : 0f, y = a.length > 4 ? Float.parseFloat(a[4]) : 0f;
                    Matrix4 m = physWorld.snap(a[0], new Matrix4()
                            .setToTranslation(Float.parseFloat(a[1]), y, Float.parseFloat(a[2])).rotate(0f, 1f, 0f, yaw));
                    physWorld.place(a[0], m); rebuild.run();
                    return "FORCED " + a[0] + " at " + m.getTranslation(new Vector3());
                }
                case "shot" -> { // shot [path]: dump the last rendered frame to a PNG (default: build/snap_shot.png)
                    String path = a.length > 0 ? a[0] : "/Users/fengyue.john.zhu/Desktop/programme/java/CircuitsLib/build/snap_shot.png";
                    com.badlogic.gdx.graphics.Pixmap p = com.badlogic.gdx.utils.ScreenUtils.getFrameBufferPixmap(
                            0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
                    com.badlogic.gdx.graphics.PixmapIO.writePNG(Gdx.files.absolute(path), p, -1, true);
                    p.dispose();
                    return "shot " + path;
                }
                case "why" -> { // why <modelId> <x> <z> [yaw] [y]: explain the placement verdict at that snapped pose
                    float yaw = a.length > 3 ? Float.parseFloat(a[3]) : 0f, y = a.length > 4 ? Float.parseFloat(a[4]) : 0f;
                    Matrix4 m = physWorld.snap(a[0], new Matrix4()
                            .setToTranslation(Float.parseFloat(a[1]), y, Float.parseFloat(a[2])).rotate(0f, 1f, 0f, yaw));
                    return physWorld.explainPlace(a[0], m);
                }
                case "place" -> { // place <modelId> cross | <x> <z> [yaw] [y]  (snap grids the yaw + lands terminals)
                    Vector3 at = new Vector3();
                    float yaw = 0f;
                    if (a[1].equalsIgnoreCase("cross")) {
                        com.badlogic.gdx.math.Intersector.intersectRayPlane(camera.getPickRay(cx(), cy()),
                                new com.badlogic.gdx.math.Plane(new Vector3(0, 1, 0), 0), at);
                    } else {
                        at.set(Float.parseFloat(a[1]), a.length > 4 ? Float.parseFloat(a[4]) : 0f, Float.parseFloat(a[2]));
                        if (a.length > 3) yaw = Float.parseFloat(a[3]);
                    }
                    Matrix4 m = physWorld.snap(a[0],
                            new Matrix4().setToTranslation(at.x, at.y, at.z).rotate(0f, 1f, 0f, yaw));
                    Vector3 got = m.getTranslation(new Vector3());
                    if (physWorld.canPlace(a[0], m)) { physWorld.place(a[0], m); rebuild.run(); return "placed " + a[0] + " at " + got; }
                    return "ERROR place " + a[0] + " BLOCKED at " + got;
                }
                default -> { return "ERROR unknown action " + verb; }
            }
        }
        @Override public Object probe(String n) {
            if (n.startsWith("part.")) { // part.x|y|z.<i>: a placement's world translation component
                String[] q = n.split("\\.");
                Vector3 t = physWorld.placements().get(Integer.parseInt(q[2])).transform().getTranslation(new Vector3());
                return q[1].equals("x") ? t.x : q[1].equals("y") ? t.y : t.z;
            }
            if (n.startsWith("motor.spin.")) return physWorld.debugSpin(Integer.parseInt(n.substring(11)));
            if (n.startsWith("cap.swell.")) return physWorld.debugSwell(Integer.parseInt(n.substring(10)));
            if (n.startsWith("sub.channel.")) return physWorld.debugChannel(Integer.parseInt(n.substring(12)));
            if (n.startsWith("sub.closed."))  return physWorld.debugSwitchClosed(Integer.parseInt(n.substring(11)));
            return switch (n) {
                case "ghost.yaw" -> physEditor.yawDeg();
                case "ghost.x" -> physEditor.ghostTransform().getTranslation(new Vector3()).x;
                case "ghost.y" -> physEditor.ghostTransform().getTranslation(new Vector3()).y;
                case "ghost.z" -> physEditor.ghostTransform().getTranslation(new Vector3()).z;
                case "ghost.anchor" -> physEditor.anchor();
                case "ghost.present" -> physEditor.present();
                case "ghost.valid" -> physEditor.valid();
                case "ghost.model" -> physEditor.modelId();
                case "scroll.accum" -> physEditor.debugScrollAccum();
                case "deck.selected" -> physEditor.deckSelected();
                case "deck.size" -> physEditor.deckSize();
                case "deck.held" -> physEditor.modelId();
                case "placed" -> physWorld.placements().size();
                case "focus.placement" -> physFocus == null ? -1 : physFocus.placementIndex();
                case "focus.sub" -> physFocus == null ? -1 : physFocus.subPart();
                case "grabbed" -> grabbed != null;
                case "cursor.caught" -> isCursorCaught.getAsBoolean();
                case "cam.yaw" -> flyCam.yawDeg();
                case "cam.pitch" -> flyCam.pitchDeg();
                case "fan.center" -> deckHud.deckAnim.center;
                case "fan.target" -> deckHud.deckAnim.target;
                case "fan.raise" -> deckHud.deckAnim.raise;
                case "net.connected" -> isConnected.getAsBoolean(); // client↔server link alive?
                case "outline.segs" -> outlineSegs;
                case "picker.open" -> deckPicker;
                case "picker.index" -> pickerIndex;
                default -> null;
            };
        }
        @Override public String[] probeNames() {
            return new String[]{"ghost.yaw", "ghost.x", "ghost.y", "ghost.z", "ghost.anchor", "ghost.present", "ghost.valid", "ghost.model",
                    "scroll.accum", "deck.selected", "deck.size", "deck.held", "placed", "focus.placement",
                    "focus.sub", "grabbed", "cursor.caught", "cam.yaw", "cam.pitch", "fan.center", "fan.target",
                    "fan.raise", "picker.open", "picker.index", "net.connected"};
        }
        @Override public void finish(int passed, int failed) {
            // Hard exit: skips dispose() on purpose so the test's placements are NEVER saved into the world file.
            System.exit(failed == 0 ? 0 : 1);
        }
    }

    /**
     * One physical-mode frame of editing: while GRABBING a knob the camera keeps turning FREELY and the knob
     * FOLLOWS the crosshair (line of sight) — it does NOT lock the view; LMB released → grab ends in touchUp.
     * Momentary controls spring back toward rest when not held. The placement ghost is suppressed while hovering
     * or dragging an interactive sub-part. {@code cursorCaught}/{@code fixedCam} are the screen's shared fly-cam
     * capture state.
     */
    void update(float dt, boolean cursorCaught, boolean fixedCam) {
        boolean grabbing = grabbed != null
                && (Gdx.input.isButtonPressed(Input.Buttons.LEFT) || scriptLmbHeld);
        if (!grabbing) grabbed = null;
        flyCam.setLookEnabled(cursorCaught && !fixedCam);
        flyCam.update(dt);
        physEditor.update(camera, physWorld);
        Ray cross = camera.getPickRay(Gdx.graphics.getWidth() / 2f, Gdx.graphics.getHeight() / 2f);
        physFocus = physWorld.focusAt(cross);
        if (grabbing && physWorld.aimSubPart(grabbed, cross)) {
            rebuild.run(); // the knob followed the aim → re-solve
        }
        // Momentary controls (push-buttons) spring back toward rest when not held; re-solve as they open.
        if (physWorld.tickMomentary(dt, grabbing ? grabbed.placementIndex() : -1)) {
            rebuild.run();
        }
        // Suppress the placement ghost while hovering (or dragging) an interactive sub-part — LMB interacts.
        boolean interactive = grabbing || physWorld.isInteractive(physFocus);
        physWorld.setGhost(!interactive && physEditor.present() && cursorCaught, physEditor.modelId(),
                physEditor.ghostTransform(), physEditor.valid(), dt);
    }

    /** Minecraft-style highlight: outline whatever the crosshair is over — a placed part (black) or one of its
     *  movable sub-parts / knobs. {@link #physFocus} was computed in {@link #update}. */
    void drawOutline() {
        if (physFocus == null) {
            return;
        }
        if (outline == null) {
            outline = new ShapeRenderer();
        }
        // The model's OWN shape (crease edges) with hidden lines removed in software (ray-cast per sample), so
        // the lines sit EXACTLY on the edges — no depth test, no bias, no expansion (owner: not bloated).
        float[] seg = physWorld.focusEdges(physFocus, camera.position);
        outlineSegs = seg.length / 6; // probe: outline.segs
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST); // draw on top: visibility was already decided per sample
        outline.setProjectionMatrix(camera.combined);
        outline.begin(ShapeRenderer.ShapeType.Line);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        outline.setColor(0f, 0f, 0f, 0.55f); // Minecraft's block outline: thin black line (a touch denser than MC's 40% — 1px on a Retina frame)
        for (int i = 0; i + 5 < seg.length; i += 6) {
            outline.line(seg[i], seg[i + 1], seg[i + 2], seg[i + 3], seg[i + 4], seg[i + 5]);
        }
        outline.end();
    }

    // ── Physical-mode input (crosshair-based; screenX/Y ignored). The screen's InputProcessor routes here. ──────

    /** LMB/RMB at the crosshair: a click while the cursor is free re-captures it; else LMB grabs a draggable
     *  sub-part or places the held part, RMB removes the part under the crosshair. Returns true if consumed. */
    boolean onTouchDown(int button) {
        if (deckPicker) {
            return true; // panel open: clicks don't re-capture the cursor or place in the world
        }
        if (!isCursorCaught.getAsBoolean()) {
            // Cursor is released for menus; a world click re-captures it (Minecraft "click to resume").
            setCursorCaught.accept(true);
            return true;
        }
        if (button == Buttons.LEFT) {
            // On an interactive sub-part, LMB INTERACTS (does not place): grab a draggable one (dragged per-frame
            // in update() while held), or open a click-UI one (stub). Else place the held part.
            if (physWorld.isDraggable(physFocus)) {
                grabbed = physFocus;
                physWorld.beginGrab(physFocus, // record the grabbed point so it stays under the cursor
                        camera.getPickRay(Gdx.graphics.getWidth() / 2f, Gdx.graphics.getHeight() / 2f));
                if (physWorld.isMomentary(physFocus)) rebuild.run(); // button pressed closed on grab
            } else if (physWorld.isClickUi(physFocus)) {
                log.info("interact: click-UI on sub-part {} of placement {} (panel TODO)",
                        physFocus.subPart(), physFocus.placementIndex());
            } else if (physEditor.place(physWorld)) {
                rebuild.run();
            }
            return true;
        }
        if (button == Buttons.RIGHT) {
            physEditor.update(camera, physWorld);
            if (physWorld.removeNear(physEditor.ghostTransform().getTranslation(new Vector3()), 18f)) {
                rebuild.run();
            }
            return true;
        }
        return false;
    }

    /** LMB up ends any knob drag (camera-look resumes next frame). */
    boolean onTouchUp(int button) {
        if (button == Buttons.LEFT) {
            grabbed = null;
        }
        return false;
    }

    /** Scroll rotates the held part (slowed: accumulates before each 90° turn, no spinning). */
    boolean onScroll(float amountY) {
        physEditor.scrollRotate(amountY);
        return true;
    }

    /** Physical-mode keys: Esc toggles the cursor (or closes the E-panel), E opens the inventory picker (and while
     *  it is open, ←/→ browse · Enter replace · [ ] add · Del remove · E/Esc close), R rotates, ←/→ select the held
     *  card, [ ] pin the anchored terminal, 1-9 jump to a deck slot. Returns true if the key was consumed. */
    boolean onKey(int keycode) {
        if (keycode == Keys.ESCAPE) {
            if (deckPicker) { deckPicker = false; setCursorCaught.accept(true); return true; } // close panel
            setCursorCaught.accept(!isCursorCaught.getAsBoolean());
            return true;
        }
        if (deckPicker) { // E-panel open: browse the catalog, then add/replace into the hand
            List<String> cat = deckHud.pickerIds();
            if (cat.isEmpty()) { deckPicker = false; return true; }
            if (keycode == Keys.LEFT)  { pickerIndex = (pickerIndex - 1 + cat.size()) % cat.size(); return true; }
            if (keycode == Keys.RIGHT) { pickerIndex = (pickerIndex + 1) % cat.size(); return true; }
            String pick = cat.get(Math.max(0, Math.min(pickerIndex, cat.size() - 1)));
            if (keycode == Keys.ENTER) {
                physEditor.deckReplace(pick); deckPicker = false; setCursorCaught.accept(true); return true;
            }
            if (keycode == Keys.LEFT_BRACKET)  { physEditor.deckAddLeft(pick); return true; }
            if (keycode == Keys.RIGHT_BRACKET) { physEditor.deckAddRight(pick); return true; }
            if (keycode == Keys.FORWARD_DEL || keycode == Keys.DEL) { physEditor.deckRemove(); return true; }
            if (keycode == Keys.E) { deckPicker = false; setCursorCaught.accept(true); return true; }
            return true; // swallow everything else while the panel is open
        }
        if (keycode == Keys.E) { // open the panel — this also ENDS any knob drag (the cursor is being released)
            grabbed = null; scriptLmbHeld = false;
            deckPicker = true; pickerIndex = 0; deckHud.resetPickerAnim(); setCursorCaught.accept(false); return true;
        }
        if (keycode == Keys.R) { physEditor.rotate(90f); return true; } // quick 90° direction turn
        // ←/→ SELECT the held card (the fan rotates it to center); [ ] pin which terminal follows the cursor.
        if (keycode == Keys.LEFT)  { physEditor.deckSelect(-1); return true; }
        if (keycode == Keys.RIGHT) { physEditor.deckSelect(1); return true; }
        if (keycode == Keys.LEFT_BRACKET)  { physEditor.cycleTerminal(-1); return true; }
        if (keycode == Keys.RIGHT_BRACKET) { physEditor.cycleTerminal(1); return true; }
        if (keycode >= Keys.NUM_1 && keycode <= Keys.NUM_9) {
            physEditor.deckSetSelected(keycode - Keys.NUM_1);
            return true;
        }
        return false;
    }
}
