package com.minecart.display;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;

public class Main {
    public static void main(String[] args) {
        if (StartupHelper.startNewJvmIfRequired()) return;
        // Pin Netty's logging backend to SLF4J before any Netty class is touched so the internal
        // logger factory caches the correct implementation. Netty auto-detects SLF4J on classpath
        // anyway; setting it explicitly makes the choice deterministic across JVMs / classloaders.
        // Note: the libGDX ApplicationLogger bridge cannot be installed here because
        // Lwjgl3Application's constructor blocks until the window closes; it's installed instead
        // from DisplayApp.create() once Gdx.app is non-null.
        InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("CircuitsLib Display");
        config.setWindowedMode(1280, 720);
        config.useVsync(true);
        config.setForegroundFPS(60);
        // Request a 24-bit depth buffer (libGDX defaults to 16-bit). With the 3D snap scene's far plane the
        // 16-bit buffer is far too coarse and the board z-fights badly; 24 bits gives ~256x the precision.
        config.setBackBufferConfig(8, 8, 8, 8, 24, 0, 0);
        new Lwjgl3Application(new DisplayApp(), config);
    }
}
