package com.minecart.spice;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the {@code libngspice} bundled in the jar for this platform is a self-contained, loadable
 * library — i.e. the app does not need a system ngspice install. The bundled binary lives at
 * {@code core/src/main/resources/<os-arch>/} (e.g. {@code darwin-aarch64/libngspice.dylib}).
 *
 * <p>These assertions look only at the classpath resource ({@link Native#extractFromResourcePath},
 * which never consults system library paths), so a pass means the <em>bundled</em> copy loaded, not a
 * Homebrew/Linux one. The test is skipped on a platform we have not bundled a binary for yet.
 */
class NgSpiceBundledTest {

    private static String bundledResourcePath() {
        // JNA maps "ngspice" -> the platform library name (libngspice.dylib / .so / ngspice.dll) and
        // looks it up under the platform resource prefix, which is exactly how we lay the file out.
        return Platform.RESOURCE_PREFIX + "/" + System.mapLibraryName("ngspice");
    }

    @Test
    void bundledLibraryIsPresentAndLoadableOnThisPlatform() throws Exception {
        URL res = NgSpiceBundledTest.class.getClassLoader().getResource(bundledResourcePath());
        assumeTrue(res != null, "no bundled libngspice for " + bundledResourcePath() + " (platform not bundled yet)");

        // Extract the bundled resource (classpath only) and load it by absolute path. If this links,
        // the bundled copy is a valid libngspice on this machine with no system install involved.
        File extracted = Native.extractFromResourcePath("ngspice", NgSpiceBundledTest.class.getClassLoader());
        assertNotNull(extracted, "extractFromResourcePath returned null");
        assertTrue(extracted.exists(), "extracted file missing: " + extracted);
        assertTrue(extracted.length() > 500_000, "bundled libngspice implausibly small: " + extracted.length());

        NgSpice.Lib lib = Native.load(extracted.getAbsolutePath(), NgSpice.Lib.class);
        assertNotNull(lib, "bundled libngspice failed to load");
    }

    @Test
    void runtimePrefersTheBundledCopy() {
        assumeTrue(NgSpice.available(), "ngspice not available on this machine");
        // With NGSPICE_LIB unset, the loader must reach for the bundled copy before any system install.
        assumeTrue(System.getenv("NGSPICE_LIB") == null || System.getenv("NGSPICE_LIB").isBlank(),
                "NGSPICE_LIB override set; bundled preference not under test");
        URL res = NgSpiceBundledTest.class.getClassLoader().getResource(bundledResourcePath());
        assumeTrue(res != null, "no bundled libngspice for this platform");
        assertTrue("bundled".equals(NgSpice.loadedFrom()),
                "expected the bundled libngspice to be loaded, but loadedFrom=" + NgSpice.loadedFrom());
    }
}
