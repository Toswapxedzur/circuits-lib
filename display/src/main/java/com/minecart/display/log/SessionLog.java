package com.minecart.display.log;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Per-game-session file logger for the desktop client.
 *
 * <p>The static {@code logback.xml} bundled with the display app only configures a console appender;
 * this class builds a {@link FileAppender} on demand and attaches it to Logback's ROOT logger when a
 * session begins, then detaches and stops it when the session ends. Because the appender is on the
 * root, every thread's log output (render thread, integrated-server tick thread, Netty I/O threads,
 * etc.) lands in the same per-session file.
 *
 * <p>Files are written to {@code data/logs/session_<yyyy-MM-dd_HHmmss>_<sanitized-label>.log} so they
 * sit next to {@code data/worlds/} and the directory shows up in the same {@code data} tree the
 * editor already creates.
 *
 * <p>Reflection / soft-dependency on Logback is intentionally avoided: this class lives in the
 * {@code display} module which pins Logback at runtime, and {@link SessionLog} is only referenced
 * from screen lifecycle code that runs after Logback has had a chance to initialise. Calling
 * {@link #begin(String)} when Logback isn't the active SLF4J binding will throw a
 * {@link ClassCastException} on the {@code (LoggerContext) LoggerFactory.getILoggerFactory()} cast —
 * which is fine: a misconfiguration that severe should fail loud, not silently log to console only.
 *
 * <p>Thread-safety: all public methods are {@code synchronized} on the class so a stray double-
 * {@code begin} from overlapping screen transitions can't leave two appenders attached. Idempotent
 * {@link #end()} (no-op when nothing is active) lets the caller wire belt-and-braces hooks without
 * worrying about ordering.
 */
public final class SessionLog {

    private static final String LOG_DIR = "data/logs";
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
    private static final String FILE_PATTERN = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n";
    private static final String APPENDER_NAME = "SESSION_FILE";

    private static FileAppender<ILoggingEvent> currentAppender;

    private SessionLog() {}

    /**
     * Begins a new session log. Any previously-active session is closed first so callers don't have
     * to track ordering across overlapping screen transitions.
     *
     * @param label short human-readable identifier (typically {@code "sp_<worldName>"} for
     *              singleplayer or {@code "mp_<host:port>"} for multiplayer). Characters outside
     *              {@code [A-Za-z0-9_-]} are folded to {@code _} so the filename stays portable.
     */
    public static synchronized void begin(String label) {
        if (currentAppender != null) {
            end();
        }

        Path logDir = Paths.get(LOG_DIR);
        try {
            Files.createDirectories(logDir);
        } catch (Exception e) {
            // Failing to create the dir is not fatal — log to console and bail; production logback.xml
            // still routes everything to STDOUT. Using SLF4J here is safe because the console appender
            // is configured statically and doesn't depend on us.
            LoggerFactory.getLogger(SessionLog.class)
                    .warn("Could not create session log directory {}: {}", logDir, e.getMessage());
            return;
        }

        String timestamp = TIMESTAMP_FMT.format(LocalDateTime.now());
        String safeLabel = sanitize(label);
        Path file = logDir.resolve("session_" + timestamp + "_" + safeLabel + ".log");

        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(ctx);
        encoder.setPattern(FILE_PATTERN);
        encoder.start();

        FileAppender<ILoggingEvent> appender = new FileAppender<>();
        appender.setContext(ctx);
        appender.setName(APPENDER_NAME);
        appender.setFile(file.toString());
        // append=false means a re-used filename (same second + same label) would truncate; in practice
        // the seconds-precision timestamp makes collisions only possible in the same JVM run, where
        // the synchronized begin/end serialises sessions anyway.
        appender.setAppend(false);
        appender.setEncoder(encoder);
        appender.start();

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);
        currentAppender = appender;

        // Mark the start in the file so a triaging human can see immediately when a session opened.
        LoggerFactory.getLogger(SessionLog.class)
                .info("Session log opened: label={} file={}", safeLabel, file);
    }

    /**
     * Detaches the current session appender from the root logger and stops it. Idempotent: no-op when
     * no session is active.
     */
    public static synchronized void end() {
        if (currentAppender == null) {
            return;
        }
        LoggerFactory.getLogger(SessionLog.class).info("Session log closing");
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(currentAppender);
        currentAppender.stop();
        currentAppender = null;
    }

    /**
     * Replace anything outside {@code [A-Za-z0-9_-]} with {@code _} and clamp to 48 chars so we don't
     * generate pathologically long filenames if a save name is huge. An empty / null label is folded
     * to {@code "session"} so the file still has a usable suffix.
     */
    private static String sanitize(String label) {
        if (label == null || label.isEmpty()) {
            return "session";
        }
        String cleaned = label.replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (cleaned.length() > 48) {
            cleaned = cleaned.substring(0, 48);
        }
        return cleaned.isEmpty() ? "session" : cleaned;
    }
}
