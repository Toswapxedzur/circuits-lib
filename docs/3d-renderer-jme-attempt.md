# Retired: jMonkeyEngine high-end 3D renderer (the `snap3d` module)

**Status:** retired 2026-08-24. Code removed from the build and deleted from the working tree;
full history preserved in git (branch `snap-3d-mode`, up to commit `4d65035`). This document is the
knowledge record so the effort can be reused elsewhere.

## Why it existed, why it was retired

The 3D snap-circuit mode wanted "epic shaders like the Complementary Minecraft shaderpack." libGDX's
fixed-function-ish g3d pipeline can't reach that without hand-writing a whole deferred/PBR stack, so we
spun up a **separate jMonkeyEngine window/process** for a high-end tier (libGDX staying as the 2D mode,
menus, and a low-end 3D fallback). jME reached the look quickly using only built-in filters.

**Retired because** the art direction changed: no sun / no environment lighting at all — the scene is lit
**only by light emitted from electrical components** — rendered with a libGDX raycast-style shader, in a
simpler LEGO-snap / "Minecraft but simpler" plastic aesthetic. A photoreal jME diorama with a sun, god
rays, IBL, and voxel terrain is the opposite of that direction, so the whole engine was cut. The libGDX
renderer (`display/.../render/snap/`) is now the single renderer.

## What worked (reusable knowledge)

### The look came entirely from built-in jME filters — no custom shaders
Stacking `FilterPostProcessor` filters on an HDR buffer nailed a Complementary-class look:
- **HDR pipeline:** `setGammaCorrection(true)` + `fpp.setFrameBufferFormat(Image.Format.RGBA16F)` +
  `ToneMapFilter` (filmic HDR→LDR, white point ~8). This is the single biggest quality lever.
- **PBR + IBL:** `PBRLighting.j3md` materials + a baked `LightProbe` via `EnvironmentCamera` /
  `LightProbeFactory.makeProbe`. Sun boosted ~3.2×, ambient ~0.26×.
- `LightScatteringFilter` (god rays) aligned to the sun position.
- `DirectionalLightShadowRenderer` / PSSM soft shadows.
- `SSAOFilter`, `BloomFilter` (exposure cutoff ~0.75 so it glints instead of hazing), `FogFilter`
  (thin, pushed far out for atmospheric depth), `FXAAFilter`.
- Gradient equirect sky via `SkyFactory.createSky(..., EquirectMap)`.
- Noise-driven low-poly clouds (fBm coverage → mass-scaled drifting puff clusters).

### Voxel (Minecraft-style) terrain — `VoxelTerrainBuilder`
Blocky terrain from a heightfield with **distance LOD by mipmap, not by bigger blocks**:
- 2.5-D surface voxels: top face + only the *exposed* side walls, grouped per block-texture → ~6 draw calls.
- Concentric rings coarsen the *geometry* outward (voxel 8→16→32) only to bound the vertex count, but the
  **apparent** block size is held constant — every quad tiles its texture at 8 units. So the horizon goes
  low-res through **mipmap texture deterioration** (Trilinear min-filter + Nearest mag + anisotropic +
  Repeat wrap), which is the correct "Distant Horizons, pre-generated" trick.
- Dramatic terrain from **ridged multifractal** (1−|noise|, squared, octave-weighted → jagged crests) +
  **domain warping** (perturbed sample coords) + a low-freq mountain mask, not plain fBm bulges. See
  `TerrainMeshBuilder`. Scene presets (`ScenePreset`, e.g. `LAKE_RING`) parameterise the macro layout.
- Textures came from a resource pack under `assets/minecraft/textures/block/`. Missing tiles (e.g.
  `grass_block_top`) were synthesized (overlay×dirt, biome-green) into a higher-priority `assets-gen`
  asset locator so the read-only pack was never modified.

### Assets
CC0 Quaternius "Ultimate Stylized Nature Pack" (glTF) worked well for props. Google-Drive folder
downloads rate-limit `gdown`; the `uc?export=download&id=<id>` direct endpoint via `curl` did not.
`BatchNode.batch()` requires uniform vertex buffers — glTF meshes have mismatched buffers, so strip
TexCoord2/3/4/Tangent/Color/Binormal via `depthFirstTraversal` + `clearBuffer` before batching.

## macOS gotchas (cost real time — keep if jME is ever revived here)
1. **Must run on JDK 21.** jme3 3.7's LWJGL 3.3.3 breaks GLFW input on JDK 24
   (`Unsupported JNI version`). The module pinned a Java-21 toolchain.
2. **No AWT with `-XstartOnFirstThread`.** `ScreenshotAppState` / `ImageIO` / `BufferedImage` deadlock
   the main thread and freeze the app after a couple seconds. Capture via macOS `screencapture` instead.
3. **The window auto-exits after ~80s when backgrounded** (never root-caused; relaunch).
4. Driving it headlessly (screenshot loop) is unreliable while the user multitasks, because
   `screencapture` grabs the frontmost window.

## Engine facts
jMonkeyEngine 3.7.0-stable (jme3-core, jme3-desktop, jme3-effects, jme3-terrain, jme3-plugins for glTF),
LWJGL 3.3.3, GLFW, OpenGL 4.1 Metal on M1 Pro. Ran as a standalone `application` module (`snap3d`) with
`mainClass = com.minecart.snap3d.Snap3DProof` and `-XstartOnFirstThread`.

The engine-agnostic geometry (`com.minecart.snap.SnapSceneGeometry` / `BoxSpec` in `:core`) was shared
between the libGDX and jME renderers and **remains** — only the jME-specific module was removed.
