package com.minecart.snap3d;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.texture.Texture;
import com.jme3.util.BufferUtils;

import java.util.Arrays;

/**
 * Blocky (Minecraft-style) terrain with pre-generated <b>distance LOD</b>: the {@link TerrainMeshBuilder}
 * heightfield is voxelised on concentric rings whose geometry step grows a little outward (finer near the
 * island, coarser at the far mountains) purely to keep the vertex budget sane. The <b>apparent</b> block size
 * stays constant everywhere — every quad tiles its texture at {@link #TEX} units — so the distant horizon goes
 * low-res through <i>mipmap texture deterioration</i>, "Distant Horizons"-style, rather than by showing bigger
 * blocks. A 2.5-D surface mesh (top face + exposed side walls) is built and grouped by block texture, so the
 * whole terrain is a handful of draw calls. Textures come from the user's resource pack in
 * {@code assets/minecraft/textures/block/}.
 */
public final class VoxelTerrainBuilder {

    // A vast, stretched world with only a gentle geometry step-up; distance "lower res" is done by MIPMAPPING
    // (the block textures blur/deteriorate with distance) rather than by obviously enlarging blocks.
    private static final float[] RING_R = {900f, 4200f, 11000f};
    private static final float[] RING_V = {8f, 16f, 32f};
    private static final float TEX = 8f; // apparent block/texel size — the texture tiles at 8 units on EVERY ring,
                                         // so distant "lower res" comes from mipmap blur, not from bigger-looking blocks

    // block ids
    private static final int SAND = 0, GRASS = 1, STONE = 2, COBBLE = 3, SNOW = 4;

    private final ScenePreset preset;
    private final TerrainMeshBuilder h; // heightfield source (ridged-multifractal LAKE_RING shape)

    private final Material sandMat, grassTopMat, grassSideMat, stoneMat, cobbleMat, snowMat, logMat, leafMat;

    public VoxelTerrainBuilder(AssetManager am, TerrainMeshBuilder heightSource, ScenePreset preset) {
        this.preset = preset;
        this.h = heightSource;
        sandMat = mat(am, "sand", ColorRGBA.White);
        grassTopMat = mat(am, "grass_block_top", ColorRGBA.White); // synthesized green top (see assets-gen)
        grassSideMat = mat(am, "grass_block_side", ColorRGBA.White);
        stoneMat = mat(am, "stone", ColorRGBA.White);
        cobbleMat = mat(am, "cobblestone", ColorRGBA.White);
        snowMat = mat(am, "quartz_block_top", new ColorRGBA(0.96f, 0.98f, 1.0f, 1f));
        logMat = mat(am, "oak_log", ColorRGBA.White);
        leafMat = mat(am, "oak_leaves", new ColorRGBA(0.36f, 0.60f, 0.26f, 1f)); // tinted (MC leaves are grayscale)
    }

    /** Dense blocky trees (log trunks + leaf canopies) scattered on grassy land — two draw calls total. */
    public Node buildTrees(int count, long seed) {
        FaceMesh logs = new FaceMesh(), leaves = new FaceMesh();
        java.util.Random r = new java.util.Random(seed);
        float s = 8f; // trees use the finest voxel
        float reach = preset.plainsOuter();
        int placed = 0, attempts = 0, max = count * 8;
        while (placed < count && attempts++ < max) {
            float x = preset.centerX() + (r.nextFloat() - 0.5f) * 2f * reach;
            float z = preset.centerZ() + (r.nextFloat() - 0.5f) * 2f * reach;
            float y = Math.round(h.height(x, z) / s) * s;
            if (y < preset.waterLine() + 8f || y > 250f) {
                continue; // grassy land only (off water, below the rock line)
            }
            float bx = Math.round(x / s) * s, bz = Math.round(z / s) * s;
            int th = 4 + r.nextInt(4); // trunk height in blocks
            for (int t = 0; t < th; t++) {
                logs.cube(bx, y + (t + 0.5f) * s, bz, s);
            }
            float top = y + th * s;
            for (int lx = -2; lx <= 2; lx++) {
                for (int lz = -2; lz <= 2; lz++) {
                    for (int ly = 0; ly <= 2; ly++) {
                        if (Math.abs(lx) == 2 && Math.abs(lz) == 2) {
                            continue; // trim the four vertical corners -> rounder canopy
                        }
                        if (ly == 2 && (Math.abs(lx) == 2 || Math.abs(lz) == 2)) {
                            continue; // taper the top
                        }
                        leaves.cube(bx + lx * s, top + (ly - 1) * s, bz + lz * s, s);
                    }
                }
            }
            placed++;
        }
        Node node = new Node("blockyTrees");
        if (!logs.empty()) {
            node.attachChild(logs.build("logs", logMat));
        }
        if (!leaves.empty()) {
            node.attachChild(leaves.build("leaves", leafMat));
        }
        node.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        return node;
    }

    /** Quantised surface height at (x,z) (voxel-top for that location's ring) — for placing props on blocks. */
    public float surfaceY(float x, float z) {
        float s = voxelSizeAt(dist(x, z));
        return Math.round(h.height(x, z) / s) * s;
    }

    public Node build() {
        FaceMesh sand = new FaceMesh(), grassTop = new FaceMesh(), grassSide = new FaceMesh();
        FaceMesh stone = new FaceMesh(), cobble = new FaceMesh(), snow = new FaceMesh();

        float rPrev = 0f;
        for (int ring = 0; ring < RING_V.length; ring++) {
            float rOut = RING_R[ring];
            float s = RING_V[ring];
            float cx = preset.centerX(), cz = preset.centerZ();
            int n = (int) Math.ceil(rOut / s);
            for (int j = -n; j <= n; j++) {
                float z = cz + (j + 0.5f) * s;
                for (int i = -n; i <= n; i++) {
                    float x = cx + (i + 0.5f) * s;
                    float r = dist(x, z);
                    if (r < rPrev || r >= rOut) {
                        continue; // this column belongs to another ring
                    }
                    float hc = h.height(x, z);
                    float hpx = h.height(x + s, z), hmx = h.height(x - s, z);
                    float hpz = h.height(x, z + s), hmz = h.height(x, z - s);
                    float y = Math.round(hc / s) * s;
                    float ny = 2f * s / (float) Math.sqrt((hmx - hpx) * (hmx - hpx) + 4f * s * s + (hmz - hpz) * (hmz - hpz));
                    int block = blockAt(y, 1f - ny);
                    FaceMesh top = topAcc(block, sand, grassTop, stone, cobble, snow);
                    FaceMesh side = sideAcc(block, sand, grassSide, stone, cobble, snow);
                    float hs = s / 2f;
                    float tRep = s / TEX; // texture tiles per quad -> constant apparent block size across rings
                    top.quad(x - hs, y, z - hs, x + hs, y, z - hs, x + hs, y, z + hs, x - hs, y, z + hs, 0, 1, 0, tRep, tRep);
                    wall(side, x, y, z, s, Math.round(hpx / s) * s, +1, 0);
                    wall(side, x, y, z, s, Math.round(hmx / s) * s, -1, 0);
                    wall(side, x, y, z, s, Math.round(hpz / s) * s, 0, +1);
                    wall(side, x, y, z, s, Math.round(hmz / s) * s, 0, -1);
                }
            }
            rPrev = rOut;
        }

        Node node = new Node("voxelTerrain");
        attach(node, sand, sandMat, "sand");
        attach(node, grassTop, grassTopMat, "grassTop");
        attach(node, grassSide, grassSideMat, "grassSide");
        attach(node, stone, stoneMat, "stone");
        attach(node, cobble, cobbleMat, "cobble");
        attach(node, snow, snowMat, "snow");
        node.setShadowMode(RenderQueue.ShadowMode.Receive);
        return node;
    }

    /** One side wall of a column toward a neighbour of quantised height {@code ny}, if the neighbour is lower. */
    private void wall(FaceMesh side, float x, float y, float z, float s, float ny, int dirX, int dirZ) {
        if (ny >= y) {
            return; // neighbour not lower -> face hidden
        }
        float hs = s / 2f;
        float uRep = s / TEX;              // horizontal tiles -> constant apparent block size
        float vRep = (y - ny) / TEX;       // vertical tiles -> one texel row per apparent block of wall height
        if (dirX > 0) {
            side.quad(x + hs, ny, z + hs, x + hs, ny, z - hs, x + hs, y, z - hs, x + hs, y, z + hs, 1, 0, 0, uRep, vRep);
        } else if (dirX < 0) {
            side.quad(x - hs, ny, z - hs, x - hs, ny, z + hs, x - hs, y, z + hs, x - hs, y, z - hs, -1, 0, 0, uRep, vRep);
        } else if (dirZ > 0) {
            side.quad(x - hs, ny, z + hs, x + hs, ny, z + hs, x + hs, y, z + hs, x - hs, y, z + hs, 0, 0, 1, uRep, vRep);
        } else {
            side.quad(x + hs, ny, z - hs, x - hs, ny, z - hs, x - hs, y, z - hs, x + hs, y, z - hs, 0, 0, -1, uRep, vRep);
        }
    }

    private int blockAt(float y, float slope) {
        if (slope > 0.62f) {
            return COBBLE; // steep faces are bare rock
        }
        if (y < preset.waterLine() + 8f) {
            return SAND;
        }
        if (y < 260f) {
            return GRASS;
        }
        if (y < 760f) {
            return STONE;
        }
        return SNOW;
    }

    private static FaceMesh topAcc(int b, FaceMesh sand, FaceMesh grassTop, FaceMesh stone, FaceMesh cobble, FaceMesh snow) {
        return switch (b) {
            case SAND -> sand;
            case GRASS -> grassTop;
            case COBBLE -> cobble;
            case SNOW -> snow;
            default -> stone;
        };
    }

    private static FaceMesh sideAcc(int b, FaceMesh sand, FaceMesh grassSide, FaceMesh stone, FaceMesh cobble, FaceMesh snow) {
        return switch (b) {
            case SAND -> sand;
            case GRASS -> grassSide;
            case COBBLE -> cobble;
            case SNOW -> snow;
            default -> stone;
        };
    }

    private float voxelSizeAt(float r) {
        for (int i = 0; i < RING_R.length; i++) {
            if (r < RING_R[i]) {
                return RING_V[i];
            }
        }
        return RING_V[RING_V.length - 1];
    }

    private float dist(float x, float z) {
        float dx = x - preset.centerX(), dz = z - preset.centerZ();
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    private static void attach(Node node, FaceMesh fm, Material mat, String name) {
        if (!fm.empty()) {
            node.attachChild(fm.build(name, mat));
        }
    }

    private Material mat(AssetManager am, String tex, ColorRGBA tint) {
        com.jme3.asset.TextureKey key = new com.jme3.asset.TextureKey("minecraft/textures/block/" + tex + ".png", false);
        key.setGenerateMips(true);
        Texture t = am.loadTexture(key);
        t.setMagFilter(Texture.MagFilter.Nearest);                 // crisp pixels up close
        t.setMinFilter(Texture.MinFilter.Trilinear);               // mipmap -> distant textures deteriorate/blur
        t.setWrap(Texture.WrapMode.Repeat);
        t.setAnisotropicFilter(4);
        Material m = new Material(am, "Common/MatDefs/Light/Lighting.j3md");
        m.setTexture("DiffuseMap", t);
        m.setBoolean("UseMaterialColors", true);
        m.setColor("Diffuse", tint);
        m.setColor("Ambient", tint);
        m.setColor("Specular", ColorRGBA.Black);
        m.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        return m;
    }

    /** Growable per-texture mesh accumulator (position/normal/uv/index). */
    private static final class FaceMesh {
        private float[] pos = new float[4096], nor = new float[4096], uv = new float[2731];
        private int[] idx = new int[6144];
        private int pn, nn, un, in, verts;

        void quad(float ax, float ay, float az, float bx, float by, float bz,
                  float cx, float cy, float cz, float dx, float dy, float dz,
                  float nx, float ny, float nz, float uMax, float vMax) {
            int base = verts;
            addPos(ax, ay, az); addPos(bx, by, bz); addPos(cx, cy, cz); addPos(dx, dy, dz);
            for (int k = 0; k < 4; k++) {
                addNor(nx, ny, nz);
            }
            addUv(0f, 0f); addUv(uMax, 0f); addUv(uMax, vMax); addUv(0f, vMax);
            addIdx(base); addIdx(base + 1); addIdx(base + 2);
            addIdx(base); addIdx(base + 2); addIdx(base + 3);
            verts += 4;
        }

        /** A full cube centred at (cx,cy,cz) with side {@code s} — all six faces, one texture tile each. */
        void cube(float cx, float cy, float cz, float s) {
            float h = s / 2f;
            float x0 = cx - h, x1 = cx + h, y0 = cy - h, y1 = cy + h, z0 = cz - h, z1 = cz + h;
            quad(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 0, 1, 0, 1f, 1f);   // top
            quad(x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, 0, -1, 0, 1f, 1f);  // bottom
            quad(x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, 1, 0, 0, 1f, 1f);   // +x
            quad(x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, -1, 0, 0, 1f, 1f);  // -x
            quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1, 1f, 1f);   // +z
            quad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1, 1f, 1f);  // -z
        }

        private void addPos(float a, float b, float c) {
            if (pn + 3 > pos.length) pos = Arrays.copyOf(pos, pos.length * 2);
            pos[pn++] = a; pos[pn++] = b; pos[pn++] = c;
        }

        private void addNor(float a, float b, float c) {
            if (nn + 3 > nor.length) nor = Arrays.copyOf(nor, nor.length * 2);
            nor[nn++] = a; nor[nn++] = b; nor[nn++] = c;
        }

        private void addUv(float a, float b) {
            if (un + 2 > uv.length) uv = Arrays.copyOf(uv, uv.length * 2);
            uv[un++] = a; uv[un++] = b;
        }

        private void addIdx(int v) {
            if (in + 1 > idx.length) idx = Arrays.copyOf(idx, idx.length * 2);
            idx[in++] = v;
        }

        boolean empty() {
            return verts == 0;
        }

        Geometry build(String name, Material mat) {
            Mesh m = new Mesh();
            m.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(Arrays.copyOf(pos, pn)));
            m.setBuffer(VertexBuffer.Type.Normal, 3, BufferUtils.createFloatBuffer(Arrays.copyOf(nor, nn)));
            m.setBuffer(VertexBuffer.Type.TexCoord, 2, BufferUtils.createFloatBuffer(Arrays.copyOf(uv, un)));
            m.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(Arrays.copyOf(idx, in)));
            m.updateBound();
            Geometry g = new Geometry(name, m);
            g.setMaterial(mat);
            return g;
        }
    }
}
