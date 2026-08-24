package com.minecart.snap3d;

import com.jme3.app.SimpleApplication;
import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingVolume;
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
import com.jme3.scene.BatchNode;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Cylinder;
import com.jme3.scene.shape.Sphere;
import com.jme3.shadow.DirectionalLightShadowRenderer;
import com.jme3.shadow.EdgeFilteringMode;
import com.jme3.system.AppSettings;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import com.jme3.util.SkyFactory;
import com.jme3.collision.CollisionResult;
import com.jme3.collision.CollisionResults;
import com.jme3.environment.EnvironmentCamera;
import com.jme3.environment.LightProbeFactory;
import com.jme3.light.LightProbe;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.MouseAxisTrigger;
import com.jme3.input.controls.MouseButtonTrigger;
import com.jme3.material.RenderState;
import com.jme3.math.Ray;
import com.minecart.snap.AllSnapParts;
import com.minecart.snap.BoxSpec;
import com.minecart.snap.Facing;
import com.minecart.snap.Post;
import com.minecart.snap.SnapBoard;
import com.minecart.snap.SnapDirections;
import com.minecart.snap.SnapPartType;
import com.minecart.snap.SnapPlacement;
import com.minecart.snap.SnapSceneGeometry;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.EnumMap;
import java.util.Map;
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
    private static final Vector3f SUN_DIR = new Vector3f(0.15f, -0.40f, 0.90f).normalizeLocal(); // ~24° so it clears the mountain ring

    private static final float WATER_HEIGHT = 10f;
    // The snap board sits on a little grassy pier just above the water, near the camera, with the lake and
    // backlit forest behind it — "build circuits in a beautiful world".
    private static final Vector3f BOARD_AT = new Vector3f(0f, WATER_HEIGHT + 4f, 60f);

    private int frame;

    // --- board + editor state ---
    private SnapBoard board;
    private Node boardNode;   // holds the committed board geometry (rebuilt on edit)
    private Node ghostNode;   // holds the translucent placement preview (rebuilt each frame)
    private Map<BoxSpec.Category, Material> boardMats;
    private Texture noiseTex; // the same 16px noise the libGDX board uses, for the pixelated surface look
    private float boardOx, boardOz; // boardNode world X/Z translation (Y is BOARD_AT.y)
    private static final float PIXELS_PER_TILE = 16f;

    private final SnapPartType[] tools = new SnapPartType[3]; // filled after AllSnapParts.init()
    private final String[] toolNames = {"Wire", "Resistor", "Battery"};
    private int toolIndex;
    private float dirAngle;        // internal continuous heading (deg)
    private float displayedAngle;  // eased heading actually drawn
    private float anchorLocalX, anchorLocalZ;
    private int anchorTerminal;
    private int anchorCol, anchorRow, anchorLayer;
    private SnapPlacement ghost;
    private boolean ghostValid;

    private BitmapText hud;
    private Material ghostValidMat, ghostInvalidMat, ghostActiveMat;
    private List<int[]> directions;
    // Movement: horizontal WASD (pitch-independent), Space/Shift vertical, Tab toggles fast.
    private boolean mFwd, mBack, mLeft, mRight, mUp, mDown, mFast;
    private static final float MOVE_SPEED = 95f, FAST_SPEED = 340f;
    private EnvironmentCamera envCam; // bakes the IBL light probe from the scene, then detaches
    private Node cloudLayer;          // drifting low-poly clouds
    private TerrainMeshBuilder terrainGen; // ridged-multifractal heightfield source
    private VoxelTerrainBuilder voxelTerrain; // blocky LOD terrain built from it
    private final List<Spatial> treeModels = new ArrayList<>(); // CC0 GLB trees dropped into resources/models
    private final List<Spatial> propModels = new ArrayList<>(); // rocks / bushes / grass etc.
    private static final float EASE_RATE = 12f;

    public static void main(String[] args) {
        Snap3DProof app = new Snap3DProof();
        AppSettings settings = new AppSettings(true);
        settings.setTitle("Snap3D (jME proof)");
        settings.setResolution(1280, 720);
        settings.setVSync(true);
        settings.setSamples(0);
        settings.setGammaCorrection(true); // render in linear space, output sRGB (correct colours + needed for PBR)
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        // Make the resource pack's block textures loadable as "minecraft/textures/block/*.png".
        // Register the generated-overlay dir FIRST (higher priority) so our synthesized textures
        // (e.g. grass_block_top, absent from the read-only pack) win; everything else falls through.
        for (String dir : new String[]{"../assets-gen", "assets-gen", "../assets", "assets"}) {
            File d = new File(dir);
            if (d.isDirectory()) {
                assetManager.registerLocator(d.getAbsolutePath(), com.jme3.asset.plugins.FileLocator.class);
            }
        }
        setDisplayStatView(false);
        setDisplayFps(false);
        flyCam.setMoveSpeed(120f);
        cam.setLocation(new Vector3f(60f, 50f, 175f));
        cam.lookAt(new Vector3f(0f, 62f, -110f), Vector3f.UNIT_Y); // toward the horizon: landscape + clouds above
        cam.setFrustumFar(24000f); // vast world — see the far mountain ring + horizon it

        rootNode.attachChild(SkyFactory.createSky(assetManager, gradientSky(), SkyFactory.EnvMapType.EquirectMap));

        DirectionalLight sun = new DirectionalLight(SUN_DIR, SUN_COLOR.mult(3.2f)); // PBR wants real light energy
        rootNode.addLight(sun);
        AmbientLight amb = new AmbientLight(AMBIENT.mult(0.26f)); // deeper shadows/contrast; IBL fills the rest
        rootNode.addLight(amb);

        // A visible sun disc in the sky (there was none before), bright HDR so bloom/tonemap make it glow and
        // the god rays emanate from it. Placed along the sun direction, well within the new far plane.
        Geometry sunDisc = new Geometry("sunDisc", new Sphere(24, 24, 650f));
        Material sunMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        sunMat.setColor("Color", new ColorRGBA(3.2f, 2.3f, 1.4f, 1f));
        sunDisc.setMaterial(sunMat);
        sunDisc.setLocalTranslation(SUN_DIR.mult(-9000f));
        sunDisc.setShadowMode(RenderQueue.ShadowMode.Off);
        rootNode.attachChild(sunDisc);

        buildTerrain();
        rootNode.attachChild(voxelTerrain.buildTrees(ScenePreset.LAKE_RING.treeCount(), 7L)); // blocky trees
        buildBlockyWater();
        buildClouds();
        buildBoard();
        setupShadows(sun);
        setupFilters(sun);
        setupCrosshairHud();
        setupEditorInput();

        // IBL: capture the scene (sky + terrain) into a light probe so PBR materials get realistic ambient
        // and environment reflections. Baked once a few frames in (see simpleUpdate), then detached.
        envCam = new EnvironmentCamera(128, new Vector3f(BOARD_AT.x, BOARD_AT.y + 40f, BOARD_AT.z));
        stateManager.attach(envCam);
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

    /** A flat, semi-transparent blocky (Minecraft-style) water plane at the water line — no smooth reflection. */
    private void buildBlockyWater() {
        float ext = 13000f;
        com.jme3.scene.shape.Quad q = new com.jme3.scene.shape.Quad(ext * 2f, ext * 2f);
        Geometry water = new Geometry("water", q);
        Material m = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        m.setColor("Color", new ColorRGBA(0.16f, 0.38f, 0.60f, 0.78f));
        m.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
        m.getAdditionalRenderState().setFaceCullMode(com.jme3.material.RenderState.FaceCullMode.Off);
        water.setMaterial(m);
        water.setQueueBucket(RenderQueue.Bucket.Transparent);
        water.rotate(-FastMath.HALF_PI, 0f, 0f);                 // XY quad -> horizontal
        water.setLocalTranslation(-ext, WATER_HEIGHT, ext);
        rootNode.attachChild(water);
    }

    private void buildTerrain() {
        // Scene preset LAKE_RING; the ridged-multifractal heightfield is voxelised into blocky LOD terrain.
        terrainGen = new TerrainMeshBuilder(new Random().nextLong(), ScenePreset.LAKE_RING);
        voxelTerrain = new VoxelTerrainBuilder(assetManager, terrainGen, ScenePreset.LAKE_RING);
        rootNode.attachChild(voxelTerrain.build());
    }

    /**
     * Loads any CC0 GLB/glTF/j3o models the user has dropped into {@code snap3d/src/main/resources/models/}
     * (e.g. Quaternius' Ultimate Stylized Nature Pack), sorting them into trees vs. ground props by filename.
     * If none are present the scene falls back to procedural cone trees, so it always renders.
     */
    private void loadNatureModels() {
        File dir = new File("src/main/resources/models");
        File[] files = dir.isDirectory() ? dir.listFiles() : null;
        if (files == null) {
            return;
        }
        for (File f : files) {
            String n = f.getName().toLowerCase();
            if (!(n.endsWith(".glb") || n.endsWith(".gltf") || n.endsWith(".j3o"))) {
                continue;
            }
            try {
                Spatial m = assetManager.loadModel("models/" + f.getName());
                m.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
                boolean prop = n.contains("rock") || n.contains("stone") || n.contains("bush") || n.contains("grass")
                        || n.contains("log") || n.contains("mushroom") || n.contains("flower") || n.contains("plant")
                        || n.contains("fern") || n.contains("stump");
                (prop ? propModels : treeModels).add(m);
                System.out.println("[JME] loaded model " + f.getName());
            } catch (Exception e) {
                System.out.println("[JME] failed to load " + f.getName() + ": " + e);
            }
        }
        System.out.println("[JME] nature models: trees=" + treeModels.size() + " props=" + propModels.size());
    }

    private void plantTrees() {
        if (terrainGen == null) {
            return;
        }
        ScenePreset p = ScenePreset.LAKE_RING;
        Random r = new Random(7);
        float reach = p.mountOuter() * 0.98f; // scatter across the plains/island, up to the mountain feet

        // Trees — dense, batched into few draw calls so thousands stay fast. Foothills only (below the snow).
        BatchNode trees = new BatchNode("trees");
        int placed = 0, attempts = 0, maxAttempts = p.treeCount() * 6;
        while (placed < p.treeCount() && attempts++ < maxAttempts) {
            float x = p.centerX() + (r.nextFloat() - 0.5f) * 2f * reach;
            float z = p.centerZ() + (r.nextFloat() - 0.5f) * 2f * reach;
            float y = voxelTerrain.surfaceY(x, z);
            if (y < p.waterLine() + 4f || y > p.treeMaxAltitude()) {
                continue;
            }
            Spatial tree = treeModels.isEmpty() ? proceduralTree(r)
                    : treeModels.get(r.nextInt(treeModels.size())).clone();
            if (treeModels.isEmpty()) {
                tree.setLocalScale(0.8f + r.nextFloat() * 1.3f);
            } else {
                fitHeight(tree, 20f + r.nextFloat() * 16f);
            }
            tree.setLocalTranslation(x, y - 1f, z);
            tree.rotate(0f, r.nextFloat() * FastMath.TWO_PI, 0f);
            trees.attachChild(tree);
            placed++;
        }
        unifyForBatch(trees);
        try {
            trees.batch();
        } catch (Exception e) {
            System.out.println("[JME] tree batch failed, rendering unbatched: " + e.getMessage());
        }
        trees.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        rootNode.attachChild(trees);

        // Rocks / bushes / grass / flowers — even denser ground cover, also batched.
        if (!propModels.isEmpty()) {
            BatchNode props = new BatchNode("props");
            placed = 0; attempts = 0; maxAttempts = p.propCount() * 6;
            while (placed < p.propCount() && attempts++ < maxAttempts) {
                float x = p.centerX() + (r.nextFloat() - 0.5f) * 2f * reach;
                float z = p.centerZ() + (r.nextFloat() - 0.5f) * 2f * reach;
                float y = voxelTerrain.surfaceY(x, z);
                if (y < p.waterLine() + 2f || y > p.treeMaxAltitude() + 120f) {
                    continue;
                }
                Spatial prop = propModels.get(r.nextInt(propModels.size())).clone();
                fitHeight(prop, 4f + r.nextFloat() * 9f);
                prop.setLocalTranslation(x, y - 1f, z);
                prop.rotate(0f, r.nextFloat() * FastMath.TWO_PI, 0f);
                props.attachChild(prop);
                placed++;
            }
            unifyForBatch(props);
            try {
                props.batch();
            } catch (Exception e) {
                System.out.println("[JME] prop batch failed, rendering unbatched: " + e.getMessage());
            }
            props.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            rootNode.attachChild(props);
        }
    }

    /** Strips optional vertex buffers so a mix of glTF meshes share a uniform layout and {@link BatchNode} can merge them. */
    private void unifyForBatch(Node node) {
        node.depthFirstTraversal(sp -> {
            if (sp instanceof Geometry) {
                Mesh m = ((Geometry) sp).getMesh();
                m.clearBuffer(VertexBuffer.Type.TexCoord2);
                m.clearBuffer(VertexBuffer.Type.TexCoord3);
                m.clearBuffer(VertexBuffer.Type.TexCoord4);
                m.clearBuffer(VertexBuffer.Type.Tangent);
                m.clearBuffer(VertexBuffer.Type.Color);
                m.clearBuffer(VertexBuffer.Type.Binormal);
            }
        });
    }

    /** A procedural cone conifer (fallback when no GLB tree models are provided). */
    private Node proceduralTree(Random r) {
        Material trunkMat = colorMat(new ColorRGBA(0.42f, 0.28f, 0.16f, 1f));
        Material leafMat = colorMat(new ColorRGBA(0.16f, 0.42f, 0.22f, 1f));
        Quaternion zUpToYUp = new Quaternion().fromAngleAxis(-FastMath.HALF_PI, Vector3f.UNIT_X);
        Node tree = new Node("tree");
        Geometry trunk = new Geometry("trunk", new Cylinder(2, 8, 0.6f, 10f, true));
        trunk.setMaterial(trunkMat);
        trunk.setLocalRotation(zUpToYUp);
        trunk.setLocalTranslation(0f, 5f, 0f);
        tree.attachChild(trunk);
        for (int c = 0; c < 3; c++) {
            Geometry cone = new Geometry("foliage", new Cylinder(2, 12, 5f - c * 1.2f, 0.01f, 6f, true, false));
            cone.setMaterial(leafMat);
            cone.setLocalRotation(zUpToYUp);
            cone.setLocalTranslation(0f, 9f + c * 4f, 0f);
            tree.attachChild(cone);
        }
        return tree;
    }

    /** Uniformly scales a loaded model so its bounding-box height is about {@code targetHeight} world units. */
    private void fitHeight(Spatial s, float targetHeight) {
        s.updateGeometricState();
        BoundingVolume bv = s.getWorldBound();
        float h = 1f;
        if (bv instanceof BoundingBox) {
            h = ((BoundingBox) bv).getYExtent() * 2f;
        }
        if (h < 1e-3f) {
            h = 1f;
        }
        s.setLocalScale(targetHeight / h);
    }

    private static final long CLOUD_SEED = 1337L;
    private static final float CLOUD_Y = 1250f; // among the mountain peaks
    private static final float CLOUD_REGION = 7000f; // half-extent of the sky field (vast world)

    /**
     * Low-poly clouds by <b>noise-driven placement</b>: scan a grid over the sky, sample fBm value-noise as a
     * coverage map, and where it exceeds a threshold spawn a faceted puff-cluster whose size/height scale with
     * the noise "mass". Fractal noise (not random scatter) is what makes clouds clump and gap naturally.
     * PBR-lit by the dawn sun; drifts on the wind (see simpleUpdate).
     */
    private void buildClouds() {
        cloudLayer = new Node("clouds");
        Material cloudMat = new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md");
        cloudMat.setColor("BaseColor", new ColorRGBA(1f, 0.97f, 0.94f, 1f));
        cloudMat.setColor("Emissive", new ColorRGBA(0.10f, 0.10f, 0.12f, 1f)); // keep them bright in shadow
        cloudMat.setFloat("Metallic", 0f);
        cloudMat.setFloat("Roughness", 1f);

        Random r = new Random(11);
        float cell = 200f, freq = 0.0020f, threshold = 0.62f; // higher threshold -> distinct clouds with sky gaps
        for (float gx = -CLOUD_REGION; gx <= CLOUD_REGION; gx += cell) {
            for (float gz = -CLOUD_REGION; gz <= CLOUD_REGION; gz += cell) {
                float wx = BOARD_AT.x + gx, wz = BOARD_AT.z + gz;
                float cover = cloudFbm(wx * freq, wz * freq);
                if (cover < threshold) {
                    continue;
                }
                float mass = (cover - threshold) / (1f - threshold); // 0..1: how thick this cloud is
                Node cloud = new Node("cloud");
                int puffs = 2 + Math.round(mass * 5f);
                for (int p = 0; p < puffs; p++) {
                    float w = (55f + mass * 150f) * (0.6f + r.nextFloat() * 0.6f);
                    float h = 16f + mass * 22f;
                    float d = (50f + mass * 120f) * (0.6f + r.nextFloat() * 0.6f);
                    Geometry puff = new Geometry("puff", new Box(w / 2f, h / 2f, d / 2f));
                    puff.setMaterial(cloudMat);
                    puff.setLocalTranslation((r.nextFloat() - 0.5f) * cell * 0.8f,
                            (r.nextFloat() - 0.5f) * mass * 26f, (r.nextFloat() - 0.5f) * cell * 0.8f);
                    cloud.attachChild(puff);
                }
                cloud.setLocalTranslation(wx + (r.nextFloat() - 0.5f) * cell * 0.4f,
                        CLOUD_Y + mass * 80f + r.nextFloat() * 30f, wz + (r.nextFloat() - 0.5f) * cell * 0.4f);
                cloud.setShadowMode(RenderQueue.ShadowMode.Off);
                cloudLayer.attachChild(cloud);
            }
        }
        rootNode.attachChild(cloudLayer);
    }

    // --- fBm value noise for cloud coverage (same technique as the terrain) ---

    private float cloudFbm(float x, float z) {
        float sum = 0f, amp = 0.5f, freq = 1f, norm = 0f;
        for (int o = 0; o < 4; o++) {
            sum += amp * cloudValueNoise(x * freq, z * freq);
            norm += amp;
            amp *= 0.5f;
            freq *= 2f;
        }
        return sum / norm;
    }

    private float cloudValueNoise(float x, float z) {
        int x0 = cloudFloor(x), z0 = cloudFloor(z);
        float tx = cloudSmooth(x - x0), tz = cloudSmooth(z - z0);
        float a = cloudHash(x0, z0), b = cloudHash(x0 + 1, z0);
        float c = cloudHash(x0, z0 + 1), d = cloudHash(x0 + 1, z0 + 1);
        float ab = a + (b - a) * tx, cd = c + (d - c) * tx;
        return ab + (cd - ab) * tz;
    }

    private static float cloudHash(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L + z * 0xC2B2AE3D27D4EB4FL + CLOUD_SEED;
        h ^= (h >>> 29);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 32);
        return (h >>> 40) / (float) (1 << 24);
    }

    private static int cloudFloor(float v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private static float cloudSmooth(float t) {
        return t * t * (3f - 2f * t);
    }

    /** The snap board, built from the shared engine-agnostic {@link SnapSceneGeometry}, on a grassy pier. */
    private void buildBoard() {
        AllSnapParts.init();
        tools[0] = AllSnapParts.SNAP_WIRE;
        tools[1] = AllSnapParts.SNAP_RESISTOR;
        tools[2] = AllSnapParts.SNAP_BATTERY;
        noiseTex = assetManager.loadTexture("textures/noise.png");
        noiseTex.setMagFilter(Texture.MagFilter.Nearest);          // crisp Minecraft-style pixels
        noiseTex.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        noiseTex.setWrap(Texture.WrapMode.Repeat);

        board = SnapBoard.createDemo();
        boardMats = boardMaterials();
        directions = SnapDirections.forLength(tools[toolIndex].length());

        float halfX = board.width() * SnapSceneGeometry.BUMP_SPACING / 2f;
        float halfZ = board.height() * SnapSceneGeometry.BUMP_SPACING / 2f;
        boardOx = BOARD_AT.x - halfX;
        boardOz = BOARD_AT.z - halfZ;

        // Grassy pier the board rests on (its top meets the board's underside; it plunges into the lake).
        float padTop = BOARD_AT.y - SnapSceneGeometry.BASE_THICKNESS;
        float padThick = 12f;
        Box padShape = new Box(halfX + 22f, padThick / 2f, halfZ + 22f);
        Geometry pad = new Geometry("pad", padShape);
        pad.setMaterial(colorMat(new ColorRGBA(0.26f, 0.44f, 0.24f, 1f)));
        pad.setLocalTranslation(BOARD_AT.x, padTop - padThick / 2f, BOARD_AT.z);
        pad.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        rootNode.attachChild(pad);

        boardNode = new Node("board");
        boardNode.setLocalTranslation(boardOx, BOARD_AT.y, boardOz);
        boardNode.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        rootNode.attachChild(boardNode);
        rebuildBoardGeometry();

        ghostNode = new Node("ghost");
        ghostNode.setLocalTranslation(boardOx, BOARD_AT.y, boardOz);
        rootNode.attachChild(ghostNode);
        setupGhostMaterials();
    }

    /** Rebuilds the committed board geometry from the current {@link SnapBoard} (call after every edit). */
    private void rebuildBoardGeometry() {
        boardNode.detachAllChildren();
        for (BoxSpec bx : SnapSceneGeometry.build(board)) {
            Box shape = new Box(bx.sizeX() / 2f, bx.sizeY() / 2f, bx.sizeZ() / 2f);
            // Tile the noise ~one texel per world unit (repeat wrap) so every surface reads as pixels.
            shape.scaleTextureCoordinates(new Vector2f(bx.sizeX() / PIXELS_PER_TILE, bx.sizeZ() / PIXELS_PER_TILE));
            Geometry g = new Geometry("boardbox", shape);
            g.setMaterial(boardMats.getOrDefault(bx.category(), boardMats.get(BoxSpec.Category.UNKNOWN)));
            g.setLocalTranslation(bx.cx(), bx.cy(), bx.cz());
            boardNode.attachChild(g);
        }
    }

    private void setupGhostMaterials() {
        ghostValidMat = translucent(new ColorRGBA(0.30f, 0.90f, 0.45f, 0.5f));
        ghostInvalidMat = translucent(new ColorRGBA(0.95f, 0.30f, 0.30f, 0.5f));
        ghostActiveMat = translucent(new ColorRGBA(0.20f, 0.90f, 1.0f, 0.75f));
    }

    private Material translucent(ColorRGBA c) {
        Material m = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        m.setColor("Color", c);
        m.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        return m;
    }

    // --- HUD + input ---

    private void setupCrosshairHud() {
        BitmapFont font = assetManager.loadFont("Interface/Fonts/Default.fnt");
        BitmapText cross = new BitmapText(font);
        cross.setText("+");
        cross.setSize(font.getCharSet().getRenderedSize() * 1.6f);
        cross.setLocalTranslation(cam.getWidth() / 2f - cross.getLineWidth() / 2f,
                cam.getHeight() / 2f + cross.getLineHeight() / 2f, 0f);
        guiNode.attachChild(cross);

        hud = new BitmapText(font);
        hud.setLocalTranslation(12f, cam.getHeight() - 8f, 0f);
        guiNode.attachChild(hud);
        updateHud();
    }

    private void updateHud() {
        if (hud != null) {
            hud.setText("Item: " + toolNames[toolIndex]
                    + "    [1-3] item   scroll: direction   L/R arrows: terminal   LMB place   RMB remove"
                    + "    WASD move  Space/Shift up/down  mouse look  Tab fast" + (mFast ? " (ON)" : "")
                    + "    layer " + anchorLayer);
        }
    }

    private void setupEditorInput() {
        flyCam.setEnabled(true);
        flyCam.setDragToRotate(true); // drag to look (no cursor capture / no drift); WASD+Space/Shift move
        flyCam.setMoveSpeed(220f);
        flyCam.setRotationSpeed(2.2f);

        inputManager.addMapping("tool1", new KeyTrigger(KeyInput.KEY_1));
        inputManager.addMapping("tool2", new KeyTrigger(KeyInput.KEY_2));
        inputManager.addMapping("tool3", new KeyTrigger(KeyInput.KEY_3));
        inputManager.addMapping("termLeft", new KeyTrigger(KeyInput.KEY_LEFT));
        inputManager.addMapping("termRight", new KeyTrigger(KeyInput.KEY_RIGHT));
        inputManager.addMapping("place", new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
        inputManager.addMapping("remove", new MouseButtonTrigger(MouseInput.BUTTON_RIGHT));
        inputManager.addMapping("dirUp", new MouseAxisTrigger(MouseInput.AXIS_WHEEL, false));
        inputManager.addMapping("dirDown", new MouseAxisTrigger(MouseInput.AXIS_WHEEL, true));

        ActionListener actions = (name, pressed, tpf) -> {
            if (!pressed) {
                return;
            }
            switch (name) {
                case "tool1" -> selectTool(0);
                case "tool2" -> selectTool(1);
                case "tool3" -> selectTool(2);
                case "termLeft" -> cycleTerminal(-1);
                case "termRight" -> cycleTerminal(1);
                case "place" -> placeAtCursor();
                case "remove" -> removeAtCursor();
                default -> { }
            }
        };
        inputManager.addListener(actions, "tool1", "tool2", "tool3", "termLeft", "termRight", "place", "remove");

        AnalogListener wheel = (name, value, tpf) -> {
            if (name.equals("dirUp")) {
                nudgeDirection(30f);
            } else if (name.equals("dirDown")) {
                nudgeDirection(-30f);
            }
        };
        inputManager.addListener(wheel, "dirUp", "dirDown");

        // Custom movement (replaces flyCam's look-direction movement — see the frame-5 mapping cleanup).
        inputManager.addMapping("MOVE_W", new KeyTrigger(KeyInput.KEY_W));
        inputManager.addMapping("MOVE_S", new KeyTrigger(KeyInput.KEY_S));
        inputManager.addMapping("MOVE_A", new KeyTrigger(KeyInput.KEY_A));
        inputManager.addMapping("MOVE_D", new KeyTrigger(KeyInput.KEY_D));
        inputManager.addMapping("MOVE_UP", new KeyTrigger(KeyInput.KEY_SPACE));
        inputManager.addMapping("MOVE_DOWN", new KeyTrigger(KeyInput.KEY_LSHIFT));
        inputManager.addMapping("MOVE_FAST", new KeyTrigger(KeyInput.KEY_TAB));
        ActionListener moveL = (name, pressed, tpf) -> {
            switch (name) {
                case "MOVE_W" -> mFwd = pressed;
                case "MOVE_S" -> mBack = pressed;
                case "MOVE_A" -> mLeft = pressed;
                case "MOVE_D" -> mRight = pressed;
                case "MOVE_UP" -> mUp = pressed;
                case "MOVE_DOWN" -> mDown = pressed;
                case "MOVE_FAST" -> { if (pressed) { mFast = !mFast; } } // toggle fast mode
                default -> { }
            }
        };
        inputManager.addListener(moveL, "MOVE_W", "MOVE_S", "MOVE_A", "MOVE_D", "MOVE_UP", "MOVE_DOWN", "MOVE_FAST");
    }

    /** Pitch-independent movement: WASD on the horizontal plane, Space/Shift vertical, Tab toggles fast. */
    private void applyMovement(float tpf) {
        float speed = (mFast ? FAST_SPEED : MOVE_SPEED) * tpf;
        Vector3f fwd = cam.getDirection().clone();
        fwd.y = 0f;
        if (fwd.lengthSquared() > 1e-5f) {
            fwd.normalizeLocal();
        }
        Vector3f leftV = cam.getLeft().clone();
        leftV.y = 0f;
        if (leftV.lengthSquared() > 1e-5f) {
            leftV.normalizeLocal();
        }
        Vector3f move = new Vector3f();
        if (mFwd) move.addLocal(fwd);
        if (mBack) move.subtractLocal(fwd);
        if (mLeft) move.addLocal(leftV);
        if (mRight) move.subtractLocal(leftV);
        if (move.lengthSquared() > 1e-5f) {
            move.normalizeLocal().multLocal(speed); // normalize so diagonals aren't faster
        }
        if (mUp) move.y += speed;
        if (mDown) move.y -= speed;
        if (move.lengthSquared() > 0f) {
            cam.setLocation(cam.getLocation().add(move));
        }
    }

    private void selectTool(int index) {
        toolIndex = index;
        directions = SnapDirections.forLength(tools[toolIndex].length());
        anchorTerminal = 0;
        updateHud();
    }

    private void cycleTerminal(int dir) {
        int n = localTerminals().length;
        anchorTerminal = ((anchorTerminal + dir) % n + n) % n;
    }

    private void nudgeDirection(float deltaDeg) {
        dirAngle = ((dirAngle + deltaDeg) % 360f + 360f) % 360f;
    }

    /** Re-targets from the crosshair ray, recomputes the ghost, and eases + redraws it each frame. */
    private void updateEditor(float dt) {
        float s = SnapSceneGeometry.BUMP_SPACING;
        Ray ray = new Ray(cam.getLocation(), cam.getDirection());
        CollisionResults res = new CollisionResults();
        boardNode.collideWith(ray, res);
        if (res.size() > 0) {
            CollisionResult hit = res.getClosestCollision();
            Vector3f p = hit.getContactPoint();
            anchorCol = clampInt(Math.round((p.x - boardOx) / s), 0, board.width());
            anchorRow = clampInt(Math.round((p.z - boardOz) / s), 0, board.height());
            anchorLayer = clampInt(Math.round((p.y - BOARD_AT.y) / SnapSceneGeometry.LEVEL_HEIGHT),
                    0, board.layers() - 1);
        } else {
            float dy = ray.getDirection().y;
            if (dy >= -1e-4f) {
                ghost = null;
                ghostNode.detachAllChildren();
                return;
            }
            float t = (BOARD_AT.y - ray.getOrigin().y) / dy;
            Vector3f p = ray.getOrigin().add(ray.getDirection().mult(t));
            anchorCol = clampInt(Math.round((p.x - boardOx) / s), 0, board.width());
            anchorRow = clampInt(Math.round((p.z - boardOz) / s), 0, board.height());
            anchorLayer = 0;
        }

        int[] d = chooseDirection(anchorCol, anchorRow, anchorLayer);
        int offCol = anchorTerminal == 0 ? 0 : d[0];
        int offRow = anchorTerminal == 0 ? 0 : d[1];
        ghost = new SnapPlacement(tools[toolIndex], anchorCol - offCol, anchorRow - offRow,
                anchorLayer, d[0], d[1], false, Double.NaN);
        ghostValid = board.canPlace(ghost);

        float targetAngle = (float) Math.toDegrees(Math.atan2(d[1], d[0]));
        float diff = ((targetAngle - displayedAngle + 540f) % 360f) - 180f;
        float k = Math.min(1f, dt * EASE_RATE);
        displayedAngle = (displayedAngle + diff * k + 360f) % 360f;
        float[] anchored = localTerminals()[anchorTerminal];
        anchorLocalX += (anchored[0] - anchorLocalX) * k;
        anchorLocalZ += (anchored[1] - anchorLocalZ) * k;

        rebuildGhost();
        updateHud();
    }

    /** Rebuilds the translucent ghost geometry (body bar + terminal bumps) around the crosshair pivot. */
    private void rebuildGhost() {
        ghostNode.detachAllChildren();
        if (ghost == null) {
            return;
        }
        float s = SnapSceneGeometry.BUMP_SPACING;
        float pivotX = anchorCol * s, pivotZ = anchorRow * s;
        float rad = displayedAngle * FastMath.DEG_TO_RAD;
        float cos = FastMath.cos(rad), sin = FastMath.sin(rad);

        float[][] terms = localTerminals();
        float[] tx = new float[terms.length], tz = new float[terms.length];
        for (int i = 0; i < terms.length; i++) {
            float lx = (terms[i][0] - anchorLocalX) * s, lz = (terms[i][1] - anchorLocalZ) * s;
            tx[i] = pivotX + lx * cos - lz * sin;
            tz[i] = pivotZ + lx * sin + lz * cos;
        }

        Material bodyMat = ghostValid ? ghostValidMat : ghostInvalidMat;
        float bodyY = SnapSceneGeometry.bodyCenterY(anchorLayer);
        float bodyLen = tools[toolIndex].length() * s + SnapSceneGeometry.COMPONENT_FOOTPRINT;
        addGhostBox((tx[0] + tx[1]) / 2f, bodyY, (tz[0] + tz[1]) / 2f,
                bodyLen, SnapSceneGeometry.COMPONENT_HEIGHT, SnapSceneGeometry.COMPONENT_FOOTPRINT,
                displayedAngle, bodyMat);

        float bumpY = SnapSceneGeometry.bumpBottomY(anchorLayer + 1) + SnapSceneGeometry.BUMP_HEIGHT / 2f;
        float w = SnapSceneGeometry.BUMP_WIDTH, h = SnapSceneGeometry.BUMP_HEIGHT;
        for (int i = 0; i < terms.length; i++) {
            addGhostBox(tx[i], bumpY, tz[i], w, h, w, 0f, i == anchorTerminal ? ghostActiveMat : bodyMat);
        }
    }

    private void addGhostBox(float cx, float cy, float cz, float sx, float sy, float sz, float yawDeg, Material mat) {
        Geometry g = new Geometry("ghostbox", new Box(sx / 2f, sy / 2f, sz / 2f));
        g.setMaterial(mat);
        g.setQueueBucket(RenderQueue.Bucket.Transparent);
        g.setLocalTranslation(cx, cy, cz);
        if (yawDeg != 0f) {
            g.setLocalRotation(new Quaternion().fromAngleAxis(-yawDeg * FastMath.DEG_TO_RAD, Vector3f.UNIT_Y));
        }
        ghostNode.attachChild(g);
    }

    private int[] chooseDirection(int col, int row, int layer) {
        int[] best = directions.get(0);
        double bestScore = Double.MAX_VALUE;
        for (int[] d : directions) {
            double ang = Math.toDegrees(Math.atan2(d[1], d[0]));
            double score = angularDistance(ang, dirAngle)
                    + (board.inBounds(new Post(col + d[0], row + d[1], layer)) ? 0 : 1000);
            if (score < bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }

    private static double angularDistance(double a, double b) {
        double d = Math.abs(a - b) % 360.0;
        return d > 180.0 ? 360.0 - d : d;
    }

    private float[][] localTerminals() {
        return new float[][]{{0f, 0f}, {tools[toolIndex].length(), 0f}};
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void placeAtCursor() {
        if (ghost != null && ghostValid && board.place(ghost)) {
            rebuildBoardGeometry();
        }
    }

    private void removeAtCursor() {
        for (SnapPlacement p : board.snapshot()) {
            Post o = p.originPost(), f = p.farPost();
            boolean here = matchesCell(o) || matchesCell(f);
            if (here) {
                board.remove(o, f);
                rebuildBoardGeometry();
                return;
            }
        }
    }

    /** A terminal is "under the crosshair" if its column/row match and its layer is the pointed or lower level. */
    private boolean matchesCell(Post t) {
        return t.col() == anchorCol && t.row() == anchorRow
                && (t.layer() == anchorLayer || t.layer() == anchorLayer - 1);
    }

    private Map<BoxSpec.Category, Material> boardMaterials() {
        Map<BoxSpec.Category, Material> m = new EnumMap<>(BoxSpec.Category.class);
        m.put(BoxSpec.Category.BASE, texturedMat(new ColorRGBA(0.16f, 0.18f, 0.22f, 1f)));
        m.put(BoxSpec.Category.BUMP, texturedMat(new ColorRGBA(0.62f, 0.64f, 0.68f, 1f)));
        m.put(BoxSpec.Category.WIRE, texturedMat(new ColorRGBA(0.85f, 0.86f, 0.90f, 1f)));
        m.put(BoxSpec.Category.RESISTOR, texturedMat(new ColorRGBA(0.86f, 0.52f, 0.20f, 1f)));
        m.put(BoxSpec.Category.BATTERY, texturedMat(new ColorRGBA(0.28f, 0.72f, 0.38f, 1f)));
        m.put(BoxSpec.Category.UNKNOWN, texturedMat(ColorRGBA.Magenta));
        return m;
    }

    /** A PBR material whose base colour is the noise texture tinted by {@code color} (the board's pixel look). */
    private Material texturedMat(ColorRGBA color) {
        Material m = new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md");
        m.setTexture("BaseColorMap", noiseTex);
        m.setColor("BaseColor", color);
        m.setFloat("Metallic", 0.0f);
        m.setFloat("Roughness", 0.75f);
        return m;
    }

    private Material colorMat(ColorRGBA color) {
        Material m = new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md");
        m.setColor("BaseColor", color);
        m.setFloat("Metallic", 0.0f);
        m.setFloat("Roughness", 0.9f);
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
        fpp.setFrameBufferFormat(com.jme3.texture.Image.Format.RGBA16F); // HDR filter buffers for real bloom/tonemap

        // (Water is now a blocky Minecraft-style plane — see buildBlockyWater(); no smooth reflective filter.)
        LightScatteringFilter godRays = new LightScatteringFilter(SUN_DIR.mult(-9000f)); // aligned with the sun disc
        godRays.setLightDensity(0.45f);
        fpp.addFilter(godRays);

        SSAOFilter ssao = new SSAOFilter(6f, 1.2f, 0.3f, 0.12f);
        fpp.addFilter(ssao);

        // Very thin fog, pushed far out for the vast world: atmospheric haze only at the distant mountain ring,
        // near/mid terrain stays crisp.
        FogFilter fog = new FogFilter(HORIZON, 0.028f, 7000f);
        fpp.addFilter(fog);

        // Bloom only on the brightest highlights, so it glints instead of hazing the frame.
        BloomFilter bloom = new BloomFilter(BloomFilter.GlowMode.Scene);
        bloom.setExposureCutOff(0.75f);
        bloom.setBloomIntensity(0.55f);
        bloom.setExposurePower(1.3f);
        bloom.setBlurScale(1.3f);
        fpp.addFilter(bloom);

        // Filmic HDR -> LDR tonemapping (rolls off the bright sky/sun/glints instead of clipping them).
        com.jme3.post.filters.ToneMapFilter toneMap = new com.jme3.post.filters.ToneMapFilter();
        toneMap.setWhitePoint(new Vector3f(8f, 8f, 8f));
        fpp.addFilter(toneMap);

        fpp.addFilter(new FXAAFilter());
        viewPort.addProcessor(fpp);
    }

    @Override
    public void simpleUpdate(float tpf) {
        updateEditor(tpf);
        if (cloudLayer != null) { // drift clouds on the wind, wrapping around
            for (Spatial c : cloudLayer.getChildren()) {
                c.move(7f * tpf, 0f, 0f);
                if (c.getLocalTranslation().x > BOARD_AT.x + CLOUD_REGION + 120f) {
                    Vector3f t = c.getLocalTranslation();
                    c.setLocalTranslation(BOARD_AT.x - CLOUD_REGION - 120f, t.y, t.z);
                }
            }
        }
        frame++;
        if (frame == 4 && envCam != null) {
            // Bake the IBL probe now that the scene has rendered a few frames. The EnvironmentCamera state
            // stays attached to finish the async bake; we just don't trigger it again.
            LightProbe probe = LightProbeFactory.makeProbe(envCam, rootNode);
            probe.getArea().setRadius(20000f);
            rootNode.addLight(probe);
            envCam = null;
        }
        if (frame == 5) {
            // flyCam registers its mappings on the first update (via FlyCamAppState). Free the wheel from its
            // FOV-zoom (scroll = placement direction), and remove its look-direction movement so our own
            // horizontal WASD + Space/Shift vertical take over. flyCam still handles mouse look.
            for (String mp : new String[]{"FLYCAM_ZoomIn", "FLYCAM_ZoomOut",
                    "FLYCAM_Forward", "FLYCAM_Backward", "FLYCAM_StrafeLeft", "FLYCAM_StrafeRight",
                    "FLYCAM_Rise", "FLYCAM_Lower"}) {
                if (inputManager.hasMapping(mp)) {
                    inputManager.deleteMapping(mp);
                }
            }
        }
        applyMovement(tpf);
    }
}
