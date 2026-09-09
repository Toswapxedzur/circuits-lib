package com.minecart.display.snap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * <b>Live console</b> — a dev-only localhost line server that lets an agent (or a human with {@code nc}) inspect and
 * drive the RUNNING app: browse any object reachable from a registered root by reflection, read/write fields, call
 * methods, stream a value over time, and execute the {@link InputScript} grammar interactively — all on the render
 * thread between two frames, so nothing freezes and every command sees (and changes) live state.
 *
 * <p>Enable with {@code -Pconsole=1} (port {@value #DEFAULT_PORT}) or {@code -Pconsole=<port>}; talk to it with
 * {@code display/scripts/live_console.py} or {@code nc 127.0.0.1 4711}. One command per line, one JSON reply line.
 *
 * <h2>Commands</h2>
 * <pre>
 * help                               this list
 * roots | ls [path]                  the roots / the fields (or elements) of the object at path, with values
 * get &lt;path&gt;                         the value at path (deep-ish summary)
 * set &lt;path&gt; &lt;value&gt;                 write a primitive / String / enum field, list or array element, map value
 * call &lt;path&gt;.&lt;method&gt;(args)        invoke a method (numbers, true/false, "strings"); returns its result
 * watch &lt;path&gt; &lt;everyMs&gt; &lt;count&gt;      sample a value over time (frames keep running)
 * probe &lt;name&gt; | probes              the harness's named state probes
 * key/hold/release/lmb/rmb/scroll/look/do/expect/dump/wait   the InputScript grammar, run live (timed ones block
 *                                    the reply until they finish — the app keeps rendering meanwhile)
 * </pre>
 * Paths: {@code root.field.sub[3].name} — {@code [i]} indexes lists/arrays, {@code [key]} maps (string or int keys).
 */
public final class LiveConsole implements AutoCloseable {

    public static final int DEFAULT_PORT = 4711;
    private static final int MAX_ELEMS = 12;

    private static final class Pending {
        final String line;
        final CompletableFuture<String> reply = new CompletableFuture<>();
        final List<String> out = new ArrayList<>();
        InputScript script;
        String watchPath; float watchEvery, watchAcc; int watchLeft;
        Pending(String line) { this.line = line; }
    }

    private final InputScript.Host host;
    private final Map<String, Object> roots = new LinkedHashMap<>();
    private final ServerSocket server;
    private final ConcurrentLinkedQueue<Pending> queue = new ConcurrentLinkedQueue<>();
    private final List<Pending> running = new ArrayList<>();

    public LiveConsole(int port, InputScript.Host host) throws IOException {
        this.host = host;
        server = new ServerSocket(port, 4, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::acceptLoop, "live-console");
        acceptor.setDaemon(true);
        acceptor.start();
        System.out.println("LIVECONSOLE listening on 127.0.0.1:" + port);
    }

    /** Registers an object the paths can start from. */
    public LiveConsole root(String name, Object o) { roots.put(name, o); return this; }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> serve(s), "live-console-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!server.isClosed()) System.out.println("LIVECONSOLE accept failed: " + e);
            }
        }
    }

    private void serve(Socket s) {
        try (s; BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                Pending p = new Pending(line);
                queue.add(p);
                String reply;
                try {
                    reply = p.reply.get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    reply = "{\"ok\":false,\"lines\":[\"ERROR " + esc(String.valueOf(e)) + "\"]}";
                }
                w.write(reply);
                w.write('\n');
                w.flush();
            }
        } catch (IOException ignored) {
            // client went away
        }
    }

    /** RENDER THREAD, once per frame: start queued commands, advance timed ones, complete replies. */
    public void drain(float dt) {
        Iterator<Pending> it = running.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            boolean done;
            try { done = step(p, dt); } catch (Throwable t) { p.out.add("ERROR " + t); done = true; }
            if (done) { it.remove(); p.reply.complete(json(p)); }
        }
        Pending p;
        while ((p = queue.poll()) != null) {
            boolean done;
            try { done = begin(p, dt); } catch (Throwable t) { p.out.add("ERROR " + t); done = true; }
            if (done) p.reply.complete(json(p)); else running.add(p);
        }
    }

    // ── command dispatch ─────────────────────────────────────────────────────────────────────────────────────

    private boolean begin(Pending p, float dt) throws Exception {
        String[] t = p.line.split("\\s+", 2);
        String op = t[0].toLowerCase();
        String rest = t.length > 1 ? t[1].strip() : "";
        switch (op) {
            case "help" -> { p.out.add("help | roots | ls [path] | get path | set path value | call path.method(args) | "
                    + "watch path everyMs count | probe name | probes | <InputScript line: key/hold/release/lmb/rmb/"
                    + "scroll/look/do/expect/dump/wait>"); return true; }
            case "roots" -> { for (Map.Entry<String, Object> e : roots.entrySet()) p.out.add(e.getKey() + " : " + cls(e.getValue())); return true; }
            case "ls" -> { ls(p, rest); return true; }
            case "get" -> { p.out.add(render(resolve(rest), 2)); return true; }
            case "set" -> { String[] a = rest.split("\\s+", 2); set(a[0], a[1]); p.out.add(a[0] + " = " + render(resolve(a[0]), 1)); return true; }
            case "call" -> { p.out.add(render(call(rest), 2)); return true; }
            case "probe" -> { p.out.add(rest + " = " + render(host.probe(rest), 1)); return true; }
            case "probes" -> { for (String n : host.probeNames()) p.out.add(n + " = " + render(host.probe(n), 1)); return true; }
            case "watch" -> {
                String[] a = rest.split("\\s+");
                p.watchPath = a[0]; p.watchEvery = Float.parseFloat(a[1]); p.watchLeft = Integer.parseInt(a[2]);
                p.out.add("t=0 " + render(resolve(p.watchPath), 1));
                p.watchLeft--;
                return p.watchLeft <= 0;
            }
            default -> {
                p.script = InputScript.load(p.line, host, false).output(p.out::add);
                p.script.tick(dt);
                return p.script.isDone();
            }
        }
    }

    private boolean step(Pending p, float dt) throws Exception {
        if (p.script != null) { p.script.tick(dt); return p.script.isDone(); }
        if (p.watchPath != null) {
            p.watchAcc += dt * 1000f;
            while (p.watchAcc >= p.watchEvery && p.watchLeft > 0) {
                p.watchAcc -= p.watchEvery; p.watchLeft--;
                p.out.add("+" + Math.round(p.watchEvery * (p.out.size())) + "ms " + render(resolve(p.watchPath), 1));
            }
            return p.watchLeft <= 0;
        }
        return true;
    }

    private void ls(Pending p, String path) throws Exception {
        if (path.isEmpty()) { for (Map.Entry<String, Object> e : roots.entrySet()) p.out.add(e.getKey() + " : " + cls(e.getValue())); return; }
        Object o = resolve(path);
        if (o == null) { p.out.add("null"); return; }
        if (o instanceof Map<?, ?> m) { int i = 0; for (Map.Entry<?, ?> e : m.entrySet()) { if (i++ >= 64) { p.out.add("… " + m.size() + " entries"); break; } p.out.add("[" + e.getKey() + "] = " + render(e.getValue(), 1)); } return; }
        if (o instanceof Collection<?> c) { int i = 0; for (Object e : c) { if (i >= 64) { p.out.add("… " + c.size() + " elements"); break; } p.out.add("[" + (i++) + "] = " + render(e, 1)); } return; }
        if (o.getClass().isArray()) { int n = Array.getLength(o); for (int i = 0; i < Math.min(n, 64); i++) p.out.add("[" + i + "] = " + render(Array.get(o, i), 1)); if (n > 64) p.out.add("… " + n + " elements"); return; }
        for (Field f : fields(o.getClass())) {
            f.setAccessible(true);
            String mod = Modifier.isStatic(f.getModifiers()) ? "static " : "";
            p.out.add(mod + f.getName() + " : " + f.getType().getSimpleName() + " = " + render(f.get(Modifier.isStatic(f.getModifiers()) ? null : o), 1));
        }
    }

    // ── reflection: paths, get, set, call ─────────────────────────────────────────────────────────────────────

    /** Splits {@code a.b[2].c[key]} into segments: names and bracketed keys ("[2]"). */
    private static List<String> segments(String path) {
        List<String> segs = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char ch = path.charAt(i);
            if (ch == '.') { if (cur.length() > 0) segs.add(cur.toString()); cur.setLength(0); }
            else if (ch == '[') { if (cur.length() > 0) segs.add(cur.toString()); cur.setLength(0); int j = path.indexOf(']', i); segs.add(path.substring(i, j + 1)); i = j; }
            else cur.append(ch);
        }
        if (cur.length() > 0) segs.add(cur.toString());
        return segs;
    }

    private Object resolve(String path) throws Exception {
        List<String> segs = segments(path);
        if (segs.isEmpty()) throw new IllegalArgumentException("empty path");
        Object o = rootOf(segs.get(0));
        for (int i = 1; i < segs.size(); i++) o = child(o, segs.get(i));
        return o;
    }

    private Object rootOf(String name) {
        if (!roots.containsKey(name)) throw new IllegalArgumentException("unknown root '" + name + "' (roots: " + roots.keySet() + ")");
        return roots.get(name);
    }

    private static Object child(Object o, String seg) throws Exception {
        if (o == null) throw new NullPointerException("null before '" + seg + "'");
        if (seg.startsWith("[")) {
            String key = seg.substring(1, seg.length() - 1);
            if (o instanceof List<?> l) return l.get(Integer.parseInt(key));
            if (o.getClass().isArray()) return Array.get(o, Integer.parseInt(key));
            if (o instanceof Map<?, ?> m) {
                if (m.containsKey(key)) return m.get(key);
                try { Integer k = Integer.valueOf(key); if (m.containsKey(k)) return m.get(k); } catch (NumberFormatException ignored) { }
                return null;
            }
            throw new IllegalArgumentException(cls(o) + " is not indexable");
        }
        Field f = field(o.getClass(), seg);
        f.setAccessible(true);
        return f.get(Modifier.isStatic(f.getModifiers()) ? null : o);
    }

    private void set(String path, String value) throws Exception {
        List<String> segs = segments(path);
        if (segs.size() < 2) throw new IllegalArgumentException("set needs root.field");
        Object parent = rootOf(segs.get(0));
        for (int i = 1; i < segs.size() - 1; i++) parent = child(parent, segs.get(i));
        String last = segs.get(segs.size() - 1);
        if (last.startsWith("[")) {
            String key = last.substring(1, last.length() - 1);
            if (parent instanceof List l) { @SuppressWarnings("unchecked") List<Object> lo = l; int i = Integer.parseInt(key); lo.set(i, coerce(value, lo.get(i) == null ? Object.class : lo.get(i).getClass())); return; }
            if (parent.getClass().isArray()) { int i = Integer.parseInt(key); Array.set(parent, i, coerce(value, parent.getClass().getComponentType())); return; }
            if (parent instanceof Map m) { @SuppressWarnings("unchecked") Map<Object, Object> mo = m; Object k = mo.containsKey(key) ? key : Integer.valueOf(key); Object old = mo.get(k); mo.put(k, coerce(value, old == null ? Object.class : old.getClass())); return; }
            throw new IllegalArgumentException(cls(parent) + " is not indexable");
        }
        Field f = field(parent.getClass(), last);
        f.setAccessible(true);
        if (parent.getClass().isRecord()) throw new IllegalArgumentException("record fields are immutable");
        f.set(Modifier.isStatic(f.getModifiers()) ? null : parent, coerce(value, f.getType()));
    }

    private Object call(String expr) throws Exception {
        int paren = expr.indexOf('(');
        if (paren < 0 || !expr.endsWith(")")) throw new IllegalArgumentException("call path.method(args)");
        String target = expr.substring(0, paren);
        int dot = target.lastIndexOf('.');
        String objPath = target.substring(0, dot), name = target.substring(dot + 1);
        Object o = resolve(objPath);
        String argStr = expr.substring(paren + 1, expr.length() - 1).strip();
        List<String> raw = new ArrayList<>();
        if (!argStr.isEmpty()) for (String a : argStr.split(",")) raw.add(a.strip());
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != raw.size()) continue;
                Object[] args = new Object[raw.size()];
                try { for (int i = 0; i < args.length; i++) args[i] = coerce(raw.get(i), m.getParameterTypes()[i]); }
                catch (RuntimeException e) { continue; }
                m.setAccessible(true);
                Object r = m.invoke(Modifier.isStatic(m.getModifiers()) ? null : o, args);
                return m.getReturnType() == void.class ? "void" : r;
            }
        }
        throw new NoSuchMethodException(name + "/" + raw.size() + " on " + cls(o));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object coerce(String v, Class<?> t) {
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) v = v.substring(1, v.length() - 1);
        if (t == String.class || t == Object.class || t == CharSequence.class) return v;
        if (t == int.class || t == Integer.class) return (int) Double.parseDouble(v);
        if (t == long.class || t == Long.class) return (long) Double.parseDouble(v);
        if (t == float.class || t == Float.class) return Float.parseFloat(v);
        if (t == double.class || t == Double.class) return Double.parseDouble(v);
        if (t == boolean.class || t == Boolean.class) return Boolean.parseBoolean(v);
        if (t == char.class || t == Character.class) return v.charAt(0);
        if (t == short.class || t == Short.class) return (short) Double.parseDouble(v);
        if (t == byte.class || t == Byte.class) return (byte) Double.parseDouble(v);
        if (t.isEnum()) return Enum.valueOf((Class<Enum>) t, v);
        throw new IllegalArgumentException("cannot coerce '" + v + "' to " + t.getSimpleName());
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { return k.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name + " on " + c.getSimpleName());
    }

    private static List<Field> fields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) if (!f.isSynthetic()) out.add(f);
        }
        return out;
    }

    // ── rendering ────────────────────────────────────────────────────────────────────────────────────────────

    private static String cls(Object o) { return o == null ? "null" : o.getClass().getSimpleName(); }

    private static boolean scalar(Object o) {
        return o == null || o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof CharSequence
                || o instanceof Enum<?> || o.getClass().getName().startsWith("com.badlogic.gdx.math.");
    }

    /** A compact value summary; {@code depth} = how many object levels to expand. */
    private static String render(Object o, int depth) {
        if (o == null) return "null";
        if (o instanceof CharSequence s) return "\"" + s + "\"";
        if (o instanceof Float f) return (f == Math.rint(f) && Math.abs(f) < 1e7) ? String.valueOf(f) : String.format("%.4g", f);
        if (o instanceof Double d) return (d == Math.rint(d) && Math.abs(d) < 1e7) ? String.valueOf(d) : String.format("%.6g", d);
        if (scalar(o)) return String.valueOf(o).replace("\n", " "); // Matrix4 etc. print multi-line
        if (o.getClass().isArray()) {
            int n = Array.getLength(o); StringBuilder sb = new StringBuilder("[" + n + ":");
            for (int i = 0; i < Math.min(n, MAX_ELEMS); i++) sb.append(i == 0 ? " " : ", ").append(render(Array.get(o, i), depth - 1));
            return sb.append(n > MAX_ELEMS ? ", …]" : "]").toString();
        }
        if (o instanceof Collection<?> c) {
            StringBuilder sb = new StringBuilder("[" + c.size() + ":"); int i = 0;
            for (Object e : c) { if (i >= MAX_ELEMS) { sb.append(", …"); break; } sb.append(i++ == 0 ? " " : ", ").append(render(e, depth - 1)); }
            return sb.append("]").toString();
        }
        if (o instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{" + m.size() + ":"); int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) { if (i >= MAX_ELEMS) { sb.append(", …"); break; } sb.append(i++ == 0 ? " " : ", ").append(e.getKey()).append('=').append(render(e.getValue(), depth - 1)); }
            return sb.append("}").toString();
        }
        if (depth <= 0) return cls(o) + "@" + Integer.toHexString(System.identityHashCode(o));
        StringBuilder sb = new StringBuilder(cls(o)).append('{');
        int i = 0;
        try {
            if (o.getClass().isRecord()) {
                for (RecordComponent rc : o.getClass().getRecordComponents()) { Method a = rc.getAccessor(); a.setAccessible(true); sb.append(i++ == 0 ? "" : ", ").append(rc.getName()).append('=').append(render(a.invoke(o), depth - 1)); }
            } else {
                for (Field f : fields(o.getClass())) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (i >= MAX_ELEMS) { sb.append(", …"); break; }
                    f.setAccessible(true);
                    Object v = f.get(o);
                    sb.append(i++ == 0 ? "" : ", ").append(f.getName()).append('=').append(scalar(v) ? render(v, 0) : render(v, depth - 1));
                }
            }
        } catch (ReflectiveOperationException e) { sb.append("?").append(e.getClass().getSimpleName()); }
        return sb.append('}').toString();
    }

    private static String json(Pending p) {
        boolean ok = true;
        StringBuilder sb = new StringBuilder("{\"ok\":");
        StringBuilder lines = new StringBuilder();
        for (String l : p.out) {
            if (l.startsWith("ERROR") || l.startsWith("INPUTTEST FAIL")) ok = false;
            lines.append(lines.length() == 0 ? "" : ",").append('"').append(esc(l)).append('"');
        }
        return sb.append(ok).append(",\"lines\":[").append(lines).append("]}").toString();
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.toString();
    }

    @Override public void close() {
        try { server.close(); } catch (IOException ignored) { }
    }
}
