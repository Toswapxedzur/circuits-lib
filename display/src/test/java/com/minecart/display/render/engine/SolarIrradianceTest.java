package com.minecart.display.render.engine;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.badlogic.gdx.math.Matrix4;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2 verification for the solar cell's light + shadow reaction ({@link PhysicalBoardView#debugIrradiance}): an
 * open cell reads full sun (1.0), a part placed overhead (up-sun of it) drops its irradiance toward zero, and a
 * non-solar part reports NaN. Headless (a {@link HeadlessApplication} supplies {@code Gdx.files} for the model
 * loader); the irradiance ray-occlusion needs no GL. Mirrors {@link PhysicalCircuitMappingTest}'s harness.
 */
class SolarIrradianceTest {

    private static HeadlessApplication app;

    @BeforeAll
    static void boot() {
        if (Gdx.app == null) {
            app = new HeadlessApplication(new ApplicationAdapter() {}, new HeadlessApplicationConfiguration());
        }
    }

    @AfterAll
    static void shutdown() {
        if (app != null) app.exit();
    }

    private static Matrix4 at(float x, float y, float z) {
        return new Matrix4().setToTranslation(x, y, z);
    }

    @Test
    void openCellReadsFullSun() {
        PhysicalBoardView board = new PhysicalBoardView();
        board.addPlacementNoRender("solar_panel", at(0f, 0f, 0f));
        assertEquals(1.0, board.debugIrradiance(0), 1e-9, "an unobstructed cell should read full sun");
    }

    @Test
    void partOverheadShadowsTheCell() {
        PhysicalBoardView board = new PhysicalBoardView();
        board.addPlacementNoRender("solar_panel", at(0f, 0f, 0f));
        double open = board.debugIrradiance(0);
        // A big tile up-sun (toward +x,+z — the sun is at (0.5,0.707,0.5)) and well above blocks the rays to the sun.
        board.addPlacementNoRender("solar_panel", at(6f, 22f, 6f));
        double shaded = board.debugIrradiance(0);
        assertEquals(1.0, open, 1e-9);
        assertTrue(shaded < open, "a part overhead must drop the cell's irradiance (was " + shaded + ")");
        assertTrue(shaded < 0.5, "a big overhead part should shadow most of the panel (was " + shaded + ")");
    }

    @Test
    void nonSolarPartReportsNaN() {
        PhysicalBoardView board = new PhysicalBoardView();
        board.addPlacementNoRender("wire_2", at(0f, 0f, 0f));
        assertTrue(Double.isNaN(board.debugIrradiance(0)), "a non-solar part has no irradiance");
    }
}
