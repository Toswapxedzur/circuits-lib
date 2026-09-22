package com.minecart.display.render.engine;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Plane;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.minecart.display.render.engine.behaviour.InteractiveBehaviour;
import com.minecart.display.render.engine.behaviour.InteractiveBehaviours;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.minecart.display.render.engine.PhysicalBoardView.Focus;
import com.minecart.display.render.engine.PhysicalBoardView.Placed;

/**
 * The interactive-controls driver for the physical board — the drag/grab bookkeeping that turns a cursor {@link Ray}
 * on a {@link Focus}ed movable sub-part into a channel value, and the per-part control state that the electrical
 * build reads back (a closed switch, a dialed resistance). Split out of {@link PhysicalBoardView} so the board keeps
 * only placement + presentation (SRP); the actual control SEMANTICS live on each part's {@link InteractiveBehaviour}
 * in the {@link InteractiveBehaviours} registry — this class only applies them.
 *
 * <p>Shares the board's {@code placed}/{@code ents} lists by reference (they stay in sync as parts are added/removed);
 * {@code subState} (placementIndex → driven channel value) is written on the render thread (drag) and read on the
 * server thread (buildCircuit), so it is a concurrent map.
 */
final class BoardInteraction {
    private final List<Placed> placed;
    private final List<EngineRenderer.DynamicEntity> ents;
    private final ModelLoader loader;

    /** Interactive sub-part state: placementIndex → the driven channel value (switch position, dial angle…). */
    private final Map<Integer, Float> subState = new ConcurrentHashMap<>();
    // Grab reference (drag-handle): the aim projection + channel value AT grab time, so the grabbed point stays under
    // the cursor as it moves (relative drag, not absolute snap).
    private float grabProj, grabChannel;
    private boolean grabValid;

    private static final float MOMENTARY_RETURN_PER_SEC = 6f; // full travel returns in ~1/6 s

    BoardInteraction(List<Placed> placed, List<EngineRenderer.DynamicEntity> ents, ModelLoader loader) {
        this.placed = placed;
        this.ents = ents;
        this.loader = loader;
    }

    /** Forgets all driven channel state (parts were cleared or indices shifted on a remove). */
    void resetState() {
        subState.clear();
    }

    /** The {@link InteractiveBehaviour} of the sub-part a {@link Focus} points at, or {@code null} if it's a
     *  base or a non-interactive movable. Matched by the movable's binding channel == the control's channel. */
    InteractiveBehaviour interactiveFor(Focus f) {
        if (f == null || f.subPart() < 0) {
            return null;
        }
        ComponentModel m = loader.model(placed.get(f.placementIndex()).modelId());
        if (f.subPart() >= m.movableParts.size()) {
            return null;
        }
        InteractiveBehaviour b = InteractiveBehaviours.of(placed.get(f.placementIndex()).modelId());
        return (b != null && b.channel().equals(m.movableParts.get(f.subPart()).binding().channel())) ? b : null;
    }

    /** The aim's raw projection in CHANNEL units. A one-axis drag has 2 DOF from the mouse but only 1 the knob may
     *  use, so we SACRIFICE the perpendicular freedom and keep only the slide axis:
     *  <ul>
     *    <li><b>drag_axis</b> — the knob is fixed to its world slide LINE {@code rest + t·worldAxis} (t in channel
     *        units). We map the mouse to it by the closest point between the pick RAY and that line — projecting the
     *        aim onto the axis and discarding the perpendicular. This is robust for ANY axis orientation (a
     *        horizontal slider or a vertically-pressed button alike) and has none of the parallax a fixed-height
     *        plane suffers when you look along it.</li>
     *    <li><b>drag_pivot</b> — SAME handle, angular form: the dial turns so the grabbed point stays collinear with
     *        the cursor and the pivot CENTRE. We sample the cursor angle in the dial's ACTUAL rotation plane (through
     *        the pivot, normal = the world rotation axis), so viewing the dial at a shallow angle doesn't parallax
     *        the collinearity. Angle (right-handed about the axis, matching the binding's rotation sense) / degPerUnit.</li>
     *  </ul>
     *  NaN if the ray can't resolve (misses the pivot plane, or runs parallel to the slide axis). */
    private float rawAim(Focus f, Ray ray) {
        InteractiveBehaviour it = interactiveFor(f);
        if (it == null) return Float.NaN;
        Placed p = placed.get(f.placementIndex());
        ComponentModel.MovablePart mv = loader.model(p.modelId()).movableParts.get(f.subPart());
        Matrix4 tf = p.transform();
        Vector3 rest = mv.local().getTranslation(new Vector3()).mul(tf);

        if (it.pivotDrag()) {
            float deg = mv.binding().degPerUnit();
            if (deg == 0f) return 0f;
            float[] ax = mv.binding().axis();
            float[] pv = mv.binding().pivot();
            Vector3 n = new Vector3(ax[0], ax[1], ax[2]).rot(tf).nor();     // world rotation axis
            Vector3 pivot = new Vector3(pv[0], pv[1], pv[2]).mul(tf);
            // Sample on the rotation plane THROUGH the pointer's height (project rest onto the axis from the pivot),
            // so the hit is the real on-face point the cursor is over — parallax-free at any view angle.
            Vector3 planePt = new Vector3(pivot).add(new Vector3(n).scl(new Vector3(rest).sub(pivot).dot(n)));
            Vector3 hit = new Vector3();
            if (!Intersector.intersectRayPlane(ray, new Plane(n, planePt), hit)) {
                return Float.NaN;
            }
            // Right-handed angle of (hit - pivot) about n, via an orthonormal in-plane basis (u, w = n×u). Matches
            // the binding, which rotates by +channel·deg about +n, so the pointer tracks the cursor's swing.
            Vector3 ref = Math.abs(n.y) < 0.99f ? new Vector3(0f, 1f, 0f) : new Vector3(1f, 0f, 0f);
            Vector3 u = new Vector3(ref).sub(new Vector3(n).scl(ref.dot(n))).nor();
            Vector3 w = new Vector3(n).crs(u);
            Vector3 v = new Vector3(hit).sub(pivot);
            return (float) Math.toDegrees(Math.atan2(v.dot(w), v.dot(u))) / deg;
        }
        // drag_axis: closest point between the pick ray (origin o, unit dir B) and the slide line (rest, A), where
        // A = worldAxis = one channel unit. Solving the 2-DOF least-squares gives the line param t straight in
        // channel units: t = ((A·B)(B·W0) - (A·W0)) / ((A·A) - (A·B)²), with W0 = rest - o and B normalised.
        float[] ax = mv.binding().axis();
        Vector3 axisW = new Vector3(ax[0], ax[1], ax[2]).rot(tf);
        if (axisW.len2() < 1e-6f) return Float.NaN;
        Vector3 dir = new Vector3(ray.direction).nor();
        Vector3 w0 = new Vector3(rest).sub(ray.origin);
        float aa = axisW.dot(axisW), ab = axisW.dot(dir), bw = dir.dot(w0), aw = axisW.dot(w0);
        float denom = aa - ab * ab;
        if (Math.abs(denom) < 1e-6f) return Float.NaN; // ray runs parallel to the slide axis — no stable answer
        return (ab * bw - aw) / denom;
    }

    /** Starts a drag on the focused sub-part: records the aim + channel at grab time, so subsequent {@link
     *  #aimSubPart} moves the knob by the DELTA (the grabbed point stays under the cursor). */
    void beginGrab(Focus f, Ray ray) {
        InteractiveBehaviour it = interactiveFor(f);
        grabValid = it != null;
        if (!grabValid) return;
        grabProj = rawAim(f, ray);
        if (it.momentary()) { // a push-button: grabbing presses it fully (closed) until released
            subState.put(f.placementIndex(), it.max());
            ents.get(f.placementIndex()).anim.set(it.channel(), it.max());
            grabChannel = it.max();
        } else {
            grabChannel = subState.getOrDefault(f.placementIndex(), it.min());
        }
        if (Float.isNaN(grabProj)) grabValid = false;
    }

    /** True if the focused sub-part is interactive (has a drag control). */
    boolean isInteractive(Focus f) {
        return interactiveFor(f) != null;
    }

    /** True if the focused sub-part is a momentary control (springs back on release). */
    boolean isMomentary(Focus f) {
        InteractiveBehaviour b = interactiveFor(f);
        return b != null && b.momentary();
    }

    /**
     * Eases every momentary control back toward its rest ({@code min}) — the button "pops up" after release —
     * except the one currently grabbed ({@code grabbedIdx}, or −1). Mirrors the eased value to the render
     * channel. Returns true if any part crossed its conduct threshold, so the caller re-solves the circuit
     * (the switch opens as the button clears mid-travel).
     */
    boolean tickMomentary(float dt, int grabbedIdx) {
        boolean rebuild = false;
        for (int i = 0; i < placed.size(); i++) {
            if (i == grabbedIdx) continue;
            InteractiveBehaviour b = InteractiveBehaviours.of(placed.get(i).modelId());
            if (b == null || !b.momentary()) continue;
            float cur = subState.getOrDefault(i, b.min());
            if (cur <= b.min() + 1e-4f) continue; // already at rest
            boolean wasClosed = b.conducts(cur);
            float next = Math.max(b.min(), cur - (b.max() - b.min()) * MOMENTARY_RETURN_PER_SEC * dt);
            subState.put(i, next);
            if (i < ents.size()) ents.get(i).anim.set(b.channel(), next);
            if (wasClosed != b.conducts(next)) rebuild = true;
        }
        return rebuild;
    }

    /** AIM-drives the grabbed sub-part: the knob FOLLOWS the crosshair (camera keeps turning freely) by the aim's
     *  DELTA from the grab, so the grabbed point stays under the cursor. Returns true if its state changed. */
    boolean aimSubPart(Focus f, Ray ray) {
        InteractiveBehaviour it = interactiveFor(f);
        if (!grabValid || it == null) return false;
        float now = rawAim(f, ray);
        if (Float.isNaN(now)) return false;
        float delta = now - grabProj;
        if (it.pivotDrag()) { // unwrap across the atan2 ±180° seam → shortest arc, never a jump
            float deg = Math.abs(loader.model(placed.get(f.placementIndex()).modelId())
                    .movableParts.get(f.subPart()).binding().degPerUnit());
            if (deg > 0f) { float rev = 360f / deg; delta -= Math.round(delta / rev) * rev; }
        }
        float c = Math.max(it.min(), Math.min(it.max(), grabChannel + delta));
        float prev = subState.getOrDefault(f.placementIndex(), it.min());
        if (Math.abs(c - prev) < 1e-4f) return false;
        subState.put(f.placementIndex(), c);
        ents.get(f.placementIndex()).anim.set(
                loader.model(placed.get(f.placementIndex()).modelId()).movableParts.get(f.subPart()).binding().channel(), c);
        return true;
    }

    /** The resistance (Ω) of placement {@code i}: a variable-resistor control maps its channel across
     *  10Ω..1000Ω; otherwise the fixed 100Ω. Read from the {@link InteractiveBehaviour}, not datagen. */
    double resistanceOhms(int i) {
        InteractiveBehaviour b = InteractiveBehaviours.of(placed.get(i).modelId());
        if (b != null && b.resistorControl()) {
            return b.resistanceOhms(subState.getOrDefault(i, b.min()));
        }
        return 100.0;
    }

    /** A switch/button part's closed state: its {@link InteractiveBehaviour} conducts past mid-travel (default
     *  OPEN at rest). Non-control conductors (plain wire/tee) always conduct. */
    boolean switchClosed(int i) {
        InteractiveBehaviour b = InteractiveBehaviours.of(placed.get(i).modelId());
        if (b != null && b.conductorControl()) {
            return b.conducts(subState.getOrDefault(i, b.min()));
        }
        return true;
    }

    /** TEST: the interactive channel value of placement {@code i} (its first movable's rest {@code min} if it was
     *  never touched); NaN if it has no interactive movable. */
    float debugChannel(int i) {
        InteractiveBehaviour b = InteractiveBehaviours.of(placed.get(i).modelId());
        return b == null ? Float.NaN : subState.getOrDefault(i, b.min());
    }
}
