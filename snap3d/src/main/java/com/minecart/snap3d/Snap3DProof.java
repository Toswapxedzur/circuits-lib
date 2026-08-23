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
import com.jme3.scene.shape.Box;
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
import com.jme3.collision.CollisionResult;
import com.jme3.collision.CollisionResults;
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

import java.nio.ByteBuffer;
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
    private static final Vector3f SUN_DIR = new Vector3f(0.10f, -0.16f, 0.90f).normalizeLocal();

    private static final float WATER_HEIGHT = 10f;
    // The snap board sits on a little grassy pier just above the water, near the camera, with the lake and
    // backlit forest behind it — "build circuits in a beautiful world".
    private static final Vector3f BOARD_AT = new Vector3f(0f, WATER_HEIGHT + 4f, 60f);

    private int frame;
    private ScreenshotAppState shot;

    // --- board + editor state ---
    private SnapBoard board;
    private Node boardNode;   // holds the committed board geometry (rebuilt on edit)
    private Node ghostNode;   // holds the translucent placement preview (rebuilt each frame)
    private Map<BoxSpec.Category, Material> boardMats;
    private float boardOx, boardOz; // boardNode world X/Z translation (Y is BOARD_AT.y)

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
    private static final float EASE_RATE = 12f;

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
        cam.setLocation(new Vector3f(48f, 34f, 138f));
        cam.lookAt(new Vector3f(0f, 15f, 30f), Vector3f.UNIT_Y); // the board, with lake + low sun behind it

        rootNode.attachChild(SkyFactory.createSky(assetManager, gradientSky(), SkyFactory.EnvMapType.EquirectMap));

        DirectionalLight sun = new DirectionalLight(SUN_DIR, SUN_COLOR.mult(1.0f));
        rootNode.addLight(sun);
        AmbientLight amb = new AmbientLight(AMBIENT);
        rootNode.addLight(amb);

        buildTerrain();
        plantTrees();
        buildBoard();
        setupShadows(sun);
        setupFilters(sun);
        setupCrosshairHud();
        setupEditorInput();

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

    /** The snap board, built from the shared engine-agnostic {@link SnapSceneGeometry}, on a grassy pier. */
    private void buildBoard() {
        AllSnapParts.init();
        tools[0] = AllSnapParts.SNAP_WIRE;
        tools[1] = AllSnapParts.SNAP_RESISTOR;
        tools[2] = AllSnapParts.SNAP_BATTERY;
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
            Geometry g = new Geometry("boardbox", new Box(bx.sizeX() / 2f, bx.sizeY() / 2f, bx.sizeZ() / 2f));
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
                    + "   WASD+mouse fly    layer " + anchorLayer);
        }
    }

    private void setupEditorInput() {
        flyCam.setMoveSpeed(90f);
        // Free the mouse wheel from flyCam's FOV-zoom so it can drive placement direction.
        inputManager.deleteMapping("FLYCAM_ZoomIn");
        inputManager.deleteMapping("FLYCAM_ZoomOut");

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
        m.put(BoxSpec.Category.BASE, colorMat(new ColorRGBA(0.16f, 0.18f, 0.22f, 1f)));
        m.put(BoxSpec.Category.BUMP, colorMat(new ColorRGBA(0.62f, 0.64f, 0.68f, 1f)));
        m.put(BoxSpec.Category.WIRE, colorMat(new ColorRGBA(0.85f, 0.86f, 0.90f, 1f)));
        m.put(BoxSpec.Category.RESISTOR, colorMat(new ColorRGBA(0.86f, 0.52f, 0.20f, 1f)));
        m.put(BoxSpec.Category.BATTERY, colorMat(new ColorRGBA(0.28f, 0.72f, 0.38f, 1f)));
        m.put(BoxSpec.Category.UNKNOWN, colorMat(ColorRGBA.Magenta));
        return m;
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
        updateEditor(tpf);
        frame++;
        if (frame == 150) {
            shot.takeScreenshot();
            System.out.println("[JME] screenshot requested -> build/jme_shot*.png");
        }
    }
}
