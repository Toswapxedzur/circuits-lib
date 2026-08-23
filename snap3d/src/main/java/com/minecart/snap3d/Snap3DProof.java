package com.minecart.snap3d;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.BloomFilter;
import com.jme3.post.filters.FXAAFilter;
import com.jme3.post.filters.FogFilter;
import com.jme3.post.filters.LightScatteringFilter;
import com.jme3.post.ssao.SSAOFilter;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Cylinder;
import com.jme3.shadow.DirectionalLightShadowRenderer;
import com.jme3.shadow.EdgeFilteringMode;
import com.jme3.system.AppSettings;
import com.jme3.terrain.geomipmap.TerrainQuad;
import com.jme3.terrain.heightmap.HillHeightMap;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import com.jme3.util.SkyFactory;

import java.nio.ByteBuffer;
import java.util.Random;

/**
 * The jMonkeyEngine dawn-diorama proof: rolling terrain, conifers, and a reflective lake, lit by a low warm
 * sun — rendered entirely through jME's built-in filters (WaterFilter reflections/waves, LightScattering
 * god rays, PSSM soft shadows, SSAO, Bloom, FXAA, fog). No hand-written shaders. This is the "can we hit the
 * Complementary look" test before wiring the 3D window to :core over the protocol.
 */
public class Snap3DProof extends SimpleApplication {

    // Dawn palette.
    private static final ColorRGBA SUN_COLOR = new ColorRGBA(1.0f, 0.66f, 0.42f, 1f);
    private static final ColorRGBA AMBIENT = new ColorRGBA(0.34f, 0.38f, 0.52f, 1f);
    private static final ColorRGBA ZENITH = new ColorRGBA(0.34f, 0.40f, 0.60f, 1f);
    private static final ColorRGBA HORIZON = new ColorRGBA(1.0f, 0.74f, 0.56f, 1f);
    private static final ColorRGBA GROUNDSKY = new ColorRGBA(0.22f, 0.18f, 0.24f, 1f);
    // Light travels toward +Z and slightly down, so the sun sits low on the -Z horizon — in front of a
    // camera that looks that way, giving backlit hills + god rays streaming toward the viewer.
    private static final Vector3f SUN_DIR = new Vector3f(0.10f, -0.16f, 0.90f).normalizeLocal();

    private static final float WATER_HEIGHT = 10f;

    private int frame;
    private ScreenshotAppState shot;

    public static void main(String[] args) {
        Snap3DProof app = new Snap3DProof();
        AppSettings settings = new AppSettings(true);
        settings.setTitle("Snap3D (jME proof)");
        settings.setResolution(1280, 720);
        settings.setVSync(true);
        settings.setSamples(0);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        setDisplayStatView(false);
        setDisplayFps(false);
        flyCam.setMoveSpeed(120f);
        cam.setLocation(new Vector3f(0f, 45f, 360f));
        cam.lookAt(new Vector3f(0f, 32f, -80f), Vector3f.UNIT_Y); // across the lake toward the low sun

        rootNode.attachChild(SkyFactory.createSky(assetManager, gradientSky(), SkyFactory.EnvMapType.EquirectMap));

        DirectionalLight sun = new DirectionalLight(SUN_DIR, SUN_COLOR.mult(1.0f));
        rootNode.addLight(sun);
        AmbientLight amb = new AmbientLight(AMBIENT);
        rootNode.addLight(amb);

        buildTerrain();
        plantTrees();
        setupShadows(sun);
        setupFilters(sun);

        shot = new ScreenshotAppState(
                "/Users/fengyue.john.zhu/Desktop/programme/java/CircuitsLib/build/", "jme_shot", 1L);
        stateManager.attach(shot);
        System.out.println("[JME] diorama initialized OK");
    }

    /** A vertical dawn gradient as an equirectangular sky (ground -> horizon -> zenith by elevation). */
    private Texture2D gradientSky() {
        int w = 16, h = 256;
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int y = 0; y < h; y++) {
            float v = y / (float) (h - 1); // 0 = down, 0.5 = horizon, 1 = up
            ColorRGBA c;
            if (v < 0.5f) {
                c = GROUNDSKY.clone().interpolateLocal(HORIZON, FastMath.clamp(v / 0.5f, 0f, 1f));
            } else {
                c = HORIZON.clone().interpolateLocal(ZENITH, FastMath.clamp((v - 0.5f) / 0.5f, 0f, 1f));
            }
            for (int x = 0; x < w; x++) {
                buf.put((byte) (c.r * 255)).put((byte) (c.g * 255)).put((byte) (c.b * 255)).put((byte) 255);
            }
        }
        buf.flip();
        Image img = new Image(Image.Format.RGBA8, w, h, buf, ColorSpace.sRGB);
        return new Texture2D(img);
    }

    private void buildTerrain() {
        try {
            HillHeightMap.NORMALIZE_RANGE = 100f;
            HillHeightMap hm = new HillHeightMap(513, 1200, 20f, 55f, new Random().nextLong());
            hm.load();
            TerrainQuad terrain = new TerrainQuad("terrain", 65, 513, hm.getHeightMap());
            Material mat = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
            mat.setBoolean("UseMaterialColors", true);
            mat.setColor("Diffuse", new ColorRGBA(0.30f, 0.46f, 0.26f, 1f));
            mat.setColor("Ambient", new ColorRGBA(0.30f, 0.46f, 0.26f, 1f));
            mat.setColor("Specular", ColorRGBA.Black);
            terrain.setMaterial(mat);
            terrain.setLocalScale(2.6f, 0.85f, 2.6f);
            terrain.setLocalTranslation(0f, -18f, 0f);
            terrain.setShadowMode(RenderQueue.ShadowMode.Receive);
            rootNode.attachChild(terrain);
        } catch (Exception e) {
            System.out.println("[JME] terrain build failed: " + e);
        }
    }

    private void plantTrees() {
        TerrainQuad terrain = (TerrainQuad) rootNode.getChild("terrain");
        if (terrain == null) {
            return;
        }
        Random r = new Random(7);
        Material trunkMat = colorMat(new ColorRGBA(0.42f, 0.28f, 0.16f, 1f));
        Material leafMat = colorMat(new ColorRGBA(0.16f, 0.42f, 0.22f, 1f));
        Quaternion zUpToYUp = new Quaternion().fromAngleAxis(-FastMath.HALF_PI, Vector3f.UNIT_X);
        for (int i = 0; i < 120; i++) {
            float x = (r.nextFloat() - 0.5f) * 900f;
            float z = (r.nextFloat() - 0.5f) * 900f;
            float y = terrain.getHeight(new Vector2f(x, z)) - 18f; // match terrain translation
            if (y < WATER_HEIGHT + 3f || y > 60f) {
                continue; // keep trees on dry land, off the peaks
            }
            float scale = 0.8f + r.nextFloat() * 1.3f;
            Node tree = new Node("tree");
            Cylinder trunkShape = new Cylinder(2, 8, 0.6f, 10f, true);
            Geometry trunk = new Geometry("trunk", trunkShape);
            trunk.setMaterial(trunkMat);
            trunk.setLocalRotation(zUpToYUp);
            trunk.setLocalTranslation(0f, 5f, 0f);
            tree.attachChild(trunk);
            for (int c = 0; c < 3; c++) {
                Cylinder coneShape = new Cylinder(2, 12, 5f - c * 1.2f, 0.01f, 6f, true, false);
                Geometry cone = new Geometry("foliage", coneShape);
                cone.setMaterial(leafMat);
                cone.setLocalRotation(zUpToYUp);
                cone.setLocalTranslation(0f, 9f + c * 4f, 0f);
                tree.attachChild(cone);
            }
            tree.setLocalScale(scale);
            tree.setLocalTranslation(x, y, z);
            tree.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            rootNode.attachChild(tree);
        }
    }

    private Material colorMat(ColorRGBA color) {
        Material m = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        m.setBoolean("UseMaterialColors", true);
        m.setColor("Diffuse", color);
        m.setColor("Ambient", color);
        m.setColor("Specular", ColorRGBA.Black);
        return m;
    }

    private void setupShadows(DirectionalLight sun) {
        DirectionalLightShadowRenderer dlsr = new DirectionalLightShadowRenderer(assetManager, 2048, 3);
        dlsr.setLight(sun);
        dlsr.setShadowIntensity(0.55f);
        dlsr.setEdgeFilteringMode(EdgeFilteringMode.PCFPOISSON);
        viewPort.addProcessor(dlsr);
    }

    private void setupFilters(DirectionalLight sun) {
        FilterPostProcessor fpp = new FilterPostProcessor(assetManager);

        com.jme3.water.WaterFilter water = new com.jme3.water.WaterFilter(rootNode, SUN_DIR);
        water.setWaterHeight(WATER_HEIGHT);
        water.setWaterColor(new ColorRGBA(0.12f, 0.28f, 0.32f, 1f));
        water.setDeepWaterColor(new ColorRGBA(0.03f, 0.10f, 0.14f, 1f));
        water.setWaterTransparency(0.10f);
        water.setWaveScale(0.006f);
        water.setMaxAmplitude(1.4f);
        water.setSpeed(0.7f);
        water.setFoamIntensity(0.5f);
        water.setLightColor(SUN_COLOR);
        water.setSunScale(1.8f);
        fpp.addFilter(water);

        LightScatteringFilter godRays = new LightScatteringFilter(SUN_DIR.mult(-3000f));
        godRays.setLightDensity(0.45f);
        fpp.addFilter(godRays);

        SSAOFilter ssao = new SSAOFilter(6f, 1.2f, 0.3f, 0.12f);
        fpp.addFilter(ssao);

        FogFilter fog = new FogFilter(HORIZON, 0.16f, 1400f);
        fpp.addFilter(fog);

        // Bloom only on the bright highlights (high cutoff) and gently, so it glows instead of blowing out.
        BloomFilter bloom = new BloomFilter(BloomFilter.GlowMode.Scene);
        bloom.setExposureCutOff(0.55f);
        bloom.setBloomIntensity(0.75f);
        bloom.setExposurePower(1.3f);
        bloom.setBlurScale(1.4f);
        fpp.addFilter(bloom);

        fpp.addFilter(new FXAAFilter());
        viewPort.addProcessor(fpp);
    }

    @Override
    public void simpleUpdate(float tpf) {
        frame++;
        if (frame == 150) {
            shot.takeScreenshot();
            System.out.println("[JME] screenshot requested -> build/jme_shot*.png");
        }
    }
}
