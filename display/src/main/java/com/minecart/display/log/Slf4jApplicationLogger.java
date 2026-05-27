package com.minecart.display.log;

import com.badlogic.gdx.ApplicationLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges libGDX's {@link com.badlogic.gdx.Application#log/error/debug} surface to SLF4J so any
 * libGDX-internal log lines (AssetManager, Lwjgl3 backend, GLProfiler, etc.) flow through Logback
 * alongside our own {@code log.info(...)} output.
 *
 * <p>The {@code tag} parameter that libGDX uses to namespace its log calls is mapped to the SLF4J
 * logger name, so a libGDX call like {@code Gdx.app.log("AssetManager", "Loaded foo.png")} surfaces
 * as if it were {@code LoggerFactory.getLogger("AssetManager").info("Loaded foo.png")}. Logger
 * instances are cached lazily by SLF4J so the mapping has no per-call overhead beyond a hashmap
 * lookup.
 *
 * <p>Install via {@link com.badlogic.gdx.Application#setApplicationLogger(ApplicationLogger)} from
 * {@code DisplayApp.create()} once {@code Gdx.app} is non-null.
 */
public final class Slf4jApplicationLogger implements ApplicationLogger {

    private static Logger forTag(String tag) {
        return LoggerFactory.getLogger(tag == null || tag.isEmpty() ? "libgdx" : tag);
    }

    @Override
    public void log(String tag, String message) {
        forTag(tag).info(message);
    }

    @Override
    public void log(String tag, String message, Throwable exception) {
        forTag(tag).info(message, exception);
    }

    @Override
    public void error(String tag, String message) {
        forTag(tag).error(message);
    }

    @Override
    public void error(String tag, String message, Throwable exception) {
        forTag(tag).error(message, exception);
    }

    @Override
    public void debug(String tag, String message) {
        forTag(tag).debug(message);
    }

    @Override
    public void debug(String tag, String message, Throwable exception) {
        forTag(tag).debug(message, exception);
    }
}
