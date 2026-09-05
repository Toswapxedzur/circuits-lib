package com.minecart.spice;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Structure.FieldOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Thin, process-wide binding to the ngspice shared library ({@code libngspice}, "sharedspice" API).
 *
 * <p>ngspice keeps global state and is not re-entrant, so this class is a singleton and every entry
 * point is {@code synchronized}. The library is located through {@code jna.library.path}, the
 * {@code NGSPICE_LIB} environment variable (a directory), or the usual Homebrew/Linux locations.
 * If it cannot be loaded, {@link #available()} is false and callers fall back to the built-in solver.
 *
 * <p>Only what the electrical backend needs is exposed: load a netlist, run a command, read a vector.
 * ngspice's console output is captured (not printed) and the last error lines are kept for diagnostics.
 */
public final class NgSpice {
    private static final Logger log = LoggerFactory.getLogger(NgSpice.class);

    /** JNA view of the C entry points we use (see sharedspice.h). */
    public interface Lib extends Library {
        int ngSpice_Init(SendChar printfcn, SendStat statfcn, ControlledExit ngexit,
                         SendData sdata, SendInitData sinitdata, BGThreadRunning bgtrun, Pointer userData);
        int ngSpice_Command(String command);
        VectorInfo.ByReference ngGet_Vec_Info(String vecname);
        int ngSpice_Circ(String[] circarray);
        String ngSpice_CurPlot();
        boolean ngSpice_running();
    }

    public interface SendChar extends Callback { int invoke(String text, int id, Pointer user); }
    public interface SendStat extends Callback { int invoke(String text, int id, Pointer user); }
    public interface ControlledExit extends Callback { int invoke(int status, boolean immediate, boolean quit, int id, Pointer user); }
    public interface SendData extends Callback { int invoke(Pointer values, int count, int id, Pointer user); }
    public interface SendInitData extends Callback { int invoke(Pointer info, int id, Pointer user); }
    public interface BGThreadRunning extends Callback { int invoke(boolean running, int id, Pointer user); }

    /** Mirrors {@code struct vector_info} in sharedspice.h. */
    @FieldOrder({"v_name", "v_type", "v_flags", "v_realdata", "v_compdata", "v_length"})
    public static class VectorInfo extends Structure {
        public String v_name;
        public int v_type;
        public short v_flags;
        public Pointer v_realdata;
        public Pointer v_compdata;
        public int v_length;

        public VectorInfo() {}
        public VectorInfo(Pointer p) { super(p); read(); }
        public static class ByReference extends VectorInfo implements Structure.ByReference {
            public ByReference() {}
            public ByReference(Pointer p) { super(p); }
        }
    }

    private static NgSpice instance;
    private static boolean loadAttempted;

    private final Lib lib;
    private final List<String> recentErrors = new ArrayList<>();
    private boolean fatal;

    // Callbacks must stay strongly referenced for the life of the process, or JNA may collect them.
    private final SendChar onChar = (text, id, user) -> { onOutput(text); return 0; };
    private final SendStat onStat = (text, id, user) -> 0;
    private final ControlledExit onExit = (status, immediate, quit, id, user) -> { fatal = true; log.error("ngspice requested exit (status {})", status); return 0; };
    private final BGThreadRunning onBg = (running, id, user) -> 0;

    private NgSpice(Lib lib) {
        this.lib = lib;
        int rc = lib.ngSpice_Init(onChar, onStat, onExit, null, null, onBg, null);
        if (rc != 0) throw new IllegalStateException("ngSpice_Init returned " + rc);
        // Quiet, deterministic defaults. No plotting, no interactive prompts.
        lib.ngSpice_Command("set ngbehavior=ltpsa");
        lib.ngSpice_Command("set nomoremode");
    }

    /** The shared instance, or {@code null} when libngspice is not loadable on this machine. */
    public static synchronized NgSpice get() {
        if (!loadAttempted) {
            loadAttempted = true;
            try {
                addSearchPaths();
                Lib lib = Native.load("ngspice", Lib.class);
                instance = new NgSpice(lib);
                log.info("ngspice shared library loaded");
            } catch (Throwable t) {
                log.warn("ngspice shared library unavailable ({}); using the built-in linear solver", t.toString());
                instance = null;
            }
        }
        return instance;
    }

    public static boolean available() {
        return get() != null;
    }

    private static void addSearchPaths() {
        String env = System.getenv("NGSPICE_LIB");
        if (env != null && !env.isBlank()) com.sun.jna.NativeLibrary.addSearchPath("ngspice", env);
        for (String dir : new String[]{"/opt/homebrew/lib", "/usr/local/lib", "/usr/lib", "/usr/lib/x86_64-linux-gnu"}) {
            com.sun.jna.NativeLibrary.addSearchPath("ngspice", dir);
        }
    }

    private void onOutput(String text) {
        if (text == null) return;
        // ngspice prefixes lines with "stdout " / "stderr ".
        String body = text.startsWith("stdout ") ? text.substring(7) : text.startsWith("stderr ") ? text.substring(7) : text;
        String lower = body.toLowerCase(Locale.ROOT);
        if (text.startsWith("stderr ") || lower.contains("error") || lower.contains("warning: singular") || lower.contains("timestep too small")) {
            synchronized (recentErrors) {
                recentErrors.add(body.strip());
                if (recentErrors.size() > 20) recentErrors.remove(0);
            }
            log.debug("ngspice: {}", body.strip());
        } else {
            log.trace("ngspice: {}", body.strip());
        }
    }

    /** Drains and returns the error/warning lines captured since the last call. */
    public synchronized List<String> drainErrors() {
        synchronized (recentErrors) {
            List<String> out = new ArrayList<>(recentErrors);
            recentErrors.clear();
            return out;
        }
    }

    public boolean isFatal() {
        return fatal;
    }

    /** Loads a netlist (first line = title, last line = {@code .end}). Replaces any circuit already loaded. */
    public synchronized boolean loadCircuit(List<String> lines) {
        drainErrors();
        lib.ngSpice_Command("destroy all");
        lib.ngSpice_Command("remcirc");
        String[] arr = new String[lines.size() + 1];
        for (int i = 0; i < lines.size(); i++) arr[i] = lines.get(i);
        arr[lines.size()] = null;
        int rc = lib.ngSpice_Circ(arr);
        return rc == 0 && !fatal;
    }

    /** Runs one interactive command ({@code run}, {@code tran ...}, {@code alter ...}). */
    public synchronized boolean command(String cmd) {
        int rc = lib.ngSpice_Command(cmd);
        return rc == 0 && !fatal;
    }

    /**
     * The last value of a vector of the current plot ({@code v(n1)}, {@code i(vm3)}, {@code time}),
     * or {@code null} when the vector does not exist or is empty.
     */
    public synchronized Double lastValue(String vector) {
        VectorInfo.ByReference v = lib.ngGet_Vec_Info(vector);
        if (v == null || v.v_length <= 0 || v.v_realdata == null) return null;
        return v.v_realdata.getDouble((long) (v.v_length - 1) * 8);
    }

    /** The whole real-valued vector, or {@code null}. */
    public synchronized double[] vector(String vector) {
        VectorInfo.ByReference v = lib.ngGet_Vec_Info(vector);
        if (v == null || v.v_length <= 0 || v.v_realdata == null) return null;
        return v.v_realdata.getDoubleArray(0, v.v_length);
    }

    public synchronized String currentPlot() {
        return lib.ngSpice_CurPlot();
    }
}
