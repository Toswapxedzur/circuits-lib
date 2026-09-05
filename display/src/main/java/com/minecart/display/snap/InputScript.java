package com.minecart.display.snap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>Scripted-input harness</b> — deterministic, in-process testing of interactive, time-based behaviour
 * (drags, wheel timing, eased animation) WITHOUT a human at the mouse and WITHOUT screenshots.
 *
 * <p>A plain-text script feeds synthetic events through the <em>same handlers</em> the real mouse/keyboard hit
 * (via a {@link Host} the screen implements), advances with the real frame loop, reads named state
 * <em>probes</em>, asserts on them, and exits the JVM non-zero if anything failed — so a gradle run is the
 * verdict. This is the general pattern recorded in {@code ~/Desktop/AGENTS.md} ("Scripted-input harness").
 *
 * <h2>Grammar</h2> one command per line, {@code #} comments; inline form joins commands with {@code ;}.
 * <pre>
 * wait &lt;ms&gt;                         let the loop run
 * key &lt;NAME&gt;                        keyDown+keyUp (NAME = a com.badlogic.gdx.Input.Keys constant, e.g. LEFT, E, ENTER)
 * hold &lt;NAME&gt; | release &lt;NAME&gt;     keyDown only / keyUp only
 * lmb down|up|click   rmb down|up|click   mouse at the screen centre (the crosshair)
 * scroll &lt;amount&gt; [x&lt;n&gt; every &lt;ms&gt;] one wheel event, or a burst of n events spaced ms apart
 * look &lt;dYawDeg&gt; &lt;dPitchDeg&gt; over &lt;ms&gt;   turn the camera by a delta, spread evenly over ms
 * do &lt;verb&gt; [args...]               a host action (setup): cursor caught|free, clear, deck add &lt;id&gt;,
 *                                   deck select &lt;i&gt;, place &lt;modelId&gt; cross | &lt;x&gt; &lt;z&gt;,
 *                                   aim &lt;placement&gt; [sub]  (point the crosshair at a hitbox centre)
 * expect &lt;probe&gt; &lt;op&gt; &lt;value|@probe&gt; [tol]   op: == != &lt; &lt;= &gt; &gt;= ~= (~= uses tol, default 1e-3)
 * dump [probe ...]                  log probe values (all known if none given)
 * end                               print the verdict and exit (implicit at end of script)
 * </pre>
 */
public final class InputScript {

    /** What the screen must provide: event sinks that route into its REAL input handlers, plus state probes. */
    public interface Host {
        void keyDown(int keycode);
        void keyUp(int keycode);
        void mouse(int button, boolean down);
        void scroll(float amountY);
        void look(float dYawDeg, float dPitchDeg);
        void action(String verb, String[] args);
        /** A named piece of state: Number / Boolean / String, or {@code null} if the probe is unknown. */
        Object probe(String name);
        /** All probe names, for {@code dump}. */
        String[] probeNames();
        /** Called once at {@code end}: log + exit the process (non-zero if {@code failed > 0}). */
        void finish(int passed, int failed);
    }

    private record Cmd(String line, String[] t) {}

    private final List<Cmd> cmds = new ArrayList<>();
    private final Host host;
    private int pc;
    private float carryMs;          // time budget carried into the current timed command
    private float progressMs;       // elapsed inside the current timed command
    private int burstLeft;          // scroll burst events still to emit
    private float lookedYaw, lookedPitch; // how much of a `look` has been applied so far
    private int passed, failed;
    private boolean done;

    private InputScript(Host host) { this.host = host; }

    /** Loads a script from a file path (absolute, or relative to the working dir) or, if no such file, treats
     *  {@code src} as inline text with {@code ;}-separated commands. */
    public static InputScript load(String src, Host host) {
        InputScript s = new InputScript(host);
        String text;
        java.io.File f = new java.io.File(src);
        try {
            text = f.isFile() ? java.nio.file.Files.readString(f.toPath()) : src.replace(';', '\n');
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("inputtest: cannot read " + src, e);
        }
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash).strip();
            if (line.isEmpty()) continue;
            s.cmds.add(new Cmd(line, line.split("\\s+")));
        }
        System.out.println("INPUTTEST loaded " + s.cmds.size() + " commands from " + (f.isFile() ? f.getPath() : "inline"));
        return s;
    }

    /** Advance the script by one frame. Immediate commands run back-to-back; timed ones consume the frame. */
    public void tick(float dt) {
        if (done) return;
        float ms = dt * 1000f;
        while (!done) {
            if (pc >= cmds.size()) { end(); return; }
            Cmd c = cmds.get(pc);
            String op = c.t()[0].toLowerCase(Locale.ROOT);
            switch (op) {
                case "wait" -> {
                    float total = num(c.t()[1]);
                    progressMs += ms;
                    if (progressMs < total) return;      // still waiting — frame consumed
                    ms = 0f; advance();
                }
                case "look" -> {
                    float dy = num(c.t()[1]), dp = num(c.t()[2]), total = num(c.t()[4]);
                    progressMs = Math.min(total, progressMs + ms);
                    float frac = total <= 0f ? 1f : progressMs / total;
                    float ty = dy * frac, tp = dp * frac;
                    host.look(ty - lookedYaw, tp - lookedPitch);
                    lookedYaw = ty; lookedPitch = tp;
                    if (progressMs < total) return;
                    ms = 0f; advance();
                }
                case "scroll" -> {
                    float amount = num(c.t()[1]);
                    if (c.t().length == 2) { host.scroll(amount); advance(); break; }
                    int n = Integer.parseInt(c.t()[2].substring(1)); // "x20"
                    float every = num(c.t()[4]);
                    if (burstLeft == 0 && progressMs == 0f) { burstLeft = n; carryMs = every; } // first fires now
                    carryMs += ms;
                    while (burstLeft > 0 && carryMs >= every) { host.scroll(amount); burstLeft--; carryMs -= every; }
                    progressMs += ms;
                    if (burstLeft > 0) return;
                    ms = 0f; advance();
                }
                case "key" -> { int k = key(c.t()[1]); host.keyDown(k); host.keyUp(k); advance(); }
                case "hold" -> { host.keyDown(key(c.t()[1])); advance(); }
                case "release" -> { host.keyUp(key(c.t()[1])); advance(); }
                case "lmb", "rmb" -> {
                    int b = op.equals("lmb") ? com.badlogic.gdx.Input.Buttons.LEFT : com.badlogic.gdx.Input.Buttons.RIGHT;
                    String how = c.t()[1].toLowerCase(Locale.ROOT);
                    if (how.equals("down") || how.equals("click")) host.mouse(b, true);
                    if (how.equals("up") || how.equals("click")) host.mouse(b, false);
                    advance();
                }
                case "do" -> {
                    String[] args = java.util.Arrays.copyOfRange(c.t(), 2, c.t().length);
                    host.action(c.t()[1].toLowerCase(Locale.ROOT), args);
                    advance();
                }
                case "expect" -> { expect(c); advance(); }
                case "dump" -> {
                    String[] names = c.t().length > 1 ? java.util.Arrays.copyOfRange(c.t(), 1, c.t().length) : host.probeNames();
                    StringBuilder sb = new StringBuilder("INPUTTEST dump");
                    for (String n : names) sb.append(' ').append(n).append('=').append(host.probe(n));
                    System.out.println(sb);
                    advance();
                }
                case "end" -> { end(); return; }
                default -> { fail(c, "unknown command"); advance(); }
            }
        }
    }

    private void advance() { pc++; progressMs = 0f; carryMs = 0f; burstLeft = 0; lookedYaw = 0f; lookedPitch = 0f; }

    private void expect(Cmd c) {
        String probe = c.t()[1], op = c.t()[2], rhs = c.t()[3];
        float tol = c.t().length > 4 ? num(c.t()[4]) : 1e-3f;
        Object actual = host.probe(probe);
        Object want = rhs.startsWith("@") ? host.probe(rhs.substring(1)) : parse(rhs);
        boolean ok;
        if (actual == null) ok = false;
        else if (actual instanceof Number a && want instanceof Number w) {
            double x = a.doubleValue(), y = w.doubleValue();
            ok = switch (op) {
                case "==" -> Math.abs(x - y) <= 1e-6;
                case "!=" -> Math.abs(x - y) > 1e-6;
                case "<"  -> x < y;  case "<=" -> x <= y;
                case ">"  -> x > y;  case ">=" -> x >= y;
                case "~=" -> Math.abs(x - y) <= tol;
                default -> false;
            };
        } else {
            boolean eq = String.valueOf(actual).equalsIgnoreCase(String.valueOf(want));
            ok = op.equals("==") || op.equals("~=") ? eq : op.equals("!=") && !eq;
        }
        if (ok) { passed++; System.out.println("INPUTTEST PASS  " + c.line() + "   (actual=" + actual + ")"); }
        else fail(c, "actual=" + actual + " want " + op + " " + want);
    }

    private void fail(Cmd c, String why) { failed++; System.out.println("INPUTTEST FAIL  " + c.line() + "   " + why); }

    private void end() {
        if (done) return;
        done = true;
        System.out.println("INPUTTEST RESULT passed=" + passed + " failed=" + failed + (failed == 0 ? "  ✅" : "  ❌"));
        System.out.flush();
        host.finish(passed, failed);
    }

    private static float num(String s) { return Float.parseFloat(s); }

    private static Object parse(String s) {
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) return Boolean.parseBoolean(s);
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return s; }
    }

    /** Key by {@link com.badlogic.gdx.Input.Keys} constant name (case-insensitive), with a few aliases. */
    private static int key(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        switch (n) {
            case "[": n = "LEFT_BRACKET"; break;
            case "]": n = "RIGHT_BRACKET"; break;
            case "DEL", "DELETE": n = "FORWARD_DEL"; break;
            case "ESC": n = "ESCAPE"; break;
            default: break;
        }
        try {
            return com.badlogic.gdx.Input.Keys.class.getField(n).getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("inputtest: unknown key " + name);
        }
    }
}
