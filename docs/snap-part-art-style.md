# Snap-part art style & the model preview

This is the art direction for the 3D snap-circuit mode's parts, the reasoning behind it, and the tooling
(`com.minecart.display.preview`) used to iterate on it. It is the reference for building every future part so
they read as one set. Written after building the first part — the **capacitor** — from scratch.

## The journey (how we got here)

1. **Engine pivot.** An earlier attempt chased photoreal "Complementary-shader" visuals in a separate
   jMonkeyEngine window (see `docs/3d-renderer-jme-attempt.md`). The art direction then changed to the
   opposite — a simple, bright, plastic LEGO look — so jME was retired and the **libGDX** renderer became the
   single 3D path. The in-game scene will be lit **only by light emitted from electrical components** (no sun,
   no environment light) via a raycast-style shader — that renderer is still to come.
2. **Model-first.** Rather than build the renderer first, we built a standalone **model preview app** to nail
   the look of one part, then will reuse the model + texture code in the real renderer.
3. **The capacitor** was the first part. It went through many small iterations on colour, band shape, noise,
   and shading — the distilled result is the "Art preferences" section below.

## The model preview app

`./gradlew :display:preview` launches `ModelPreviewApp` (libGDX, LWJGL3). Bright, even **studio lighting**
(this is a viewer, not the game scene, so the no-sun rule does not apply here); drag to orbit, scroll to zoom.

**Workflow rule — show variants, not one result.** When iterating on a look, the app renders **several
parameter variants side by side**, each labelled with its params, so a good candidate is picked at a glance
instead of one slow round-trip at a time. Set the `VARIANTS` array; the row auto-frames. (Once a look is
chosen, collapse `VARIANTS` to the single winner.)

Files (all in `display/src/main/java/com/minecart/display/preview/`):
- `ModelPreviewApp` — the viewer, camera, studio lights, the variant row + labels.
- `PreviewPart` — builds the capacitor model (geometry + per-face lit textures). Uses the exact unit system
  of `com.minecart.snap.SnapSceneGeometry` (1 texel = 1 world unit) so models drop straight into the renderer.
- `PreviewTextures` — the palette + procedural texture generation (below).

## The capacitor model

A **1×2** part (spans two snap posts). Dimensions from `SnapSceneGeometry`:
- **Body**: a `25 × 4 × 9` bar (length × height × footprint). Sliced in height so a **white label band wraps
  all four sides from y=1 to y=3**: green rim `[0,1]` · white band `[1,3]` · green rim `[3,4]`. Top and bottom
  stay green.
- **Snap studs**: a metallic `3 × 1 × 3` stud on top of each terminal post (x = ±8). These are the LEGO-style
  press-studs — the "snap like lego" connectors.

## Texture & shading system

Everything is a **small conserved palette** drawn per face at **1 texel = 1 world unit** (crisp, non-tiling).
Continuous random noise (TV static) and continuous colour multiplies are both avoided.

- **Palette conservation.** Only the palette's exact colours ever appear. Body = 7-shade lime; white band =
  6 near-white greys; steel studs = 5 steel-blue shades.
- **Shading via palette bias (not colour multiply).** Each texel is generated in world space; its local *lit
  value* (how much the point faces the light) **shifts which palette shade it is likely to pick** — brighter
  light → higher probability of a brighter shade. So light dithers across the fixed palette instead of
  blending colours off-palette. The gradient is normalised by the part's extent **along the light direction**,
  so it spans the part whatever the direction (a vertical light shades over the short height, etc.).
- **Grain (body/band): random, subtle, independent.** A medium-offset per-pixel grain on top of the lit shade.
  Chosen independently per pixel (a neighbour-relative walk was tried and rejected — it diffused into big
  blobs). `zeroWeight` controls how much: higher = calmer/more lighting-driven.
- **Grain (metal studs): ordered (Bayer) dithering.** Random grain on the small studs looked either
  salt-and-pepper (bright next to dark) or, when calmed, formed a hard band. A 4×4 **Bayer dither** of the lit
  gradient fixes both: neighbours differ by at most one shade (no bright-next-to-dark) and shade bands break
  into a fine interleave (no clusters), while still strictly following the light.
- **Repeated instances share a texture.** The two studs are shaded in their **own local frame** (centre = the
  stud), so both come out with an **identical** texture regardless of the global light direction, and use the
  same seed. Duplicate parts should look identical, not randomly different.

## Art preferences (the rules, distilled)

Follow these for every part:

- **Vibe:** LEGO-snap plastic pieces that look satisfying to snap together — "Minecraft, but even simpler."
  Bright, low-poly, **plastic** (matte, *not* glossy/metallic) for bodies; metal only where it's really metal
  (the snap studs). No shader gloss on plastic.
- **Colour:** vanilla-Minecraft-wool style. The capacitor body is **lime-wool green** — bright, a touch
  yellow, saturated but not neon. Push a target colour a little *brighter* than the reference because the
  noise + ambient dim it slightly.
- **The brightest shade should read genuinely bright** — bake the colour into the palette (RGB) rather than a
  grayscale multiplier, so the top shade can go above the base toward a pale highlight.
- **Palette:** small (5–7 shades), hand-tuned, **not** linearly interpolated (avoid muddy mid-tones), **low
  contrast** — the shades sit close together with only the top shade reaching a highlight.
- **Noise:** subtle, quantised to the palette, **no large clusters**, **no salt-and-pepper**. Variation should
  *follow the lighting* — a bright pixel should not sit next to a dark pixel (except across a top→side face
  seam, which is a different face and may jump).
- **Lighting:** soft and fairly **uniform** — do **not** let one face (e.g. the top) pop much brighter than
  the sides. A gentle **top-lit** gradient so the bottom is *a little* darker than the top is good; keep it
  subtle. The chosen capacitor light is top-lit, direction ≈ `(0.5, 0.7, 0.4)`, `shift` 3.5.
- **White = white plastic** (bright, near-white, only lightly shaded), not grey/off-white.
- **Metal = steel-blue** (light-blue highlight → grey steel), clearly lit, ordered-dithered.
- **Duplicated parts look identical** to each other.

## Chosen capacitor parameters (as committed)

- Light: top-lit `(0.5, 0.7, 0.4)`, `shift` 3.5.
- Body: `PreviewTextures.limeWool()` (7 shades), white diffuse, random grain `grainMax` 2 / `zeroWeight` 0.3.
- Band: `grays(6, 0.85, 1.0)` × diffuse `(0.97, 0.97, 0.96)`, random grain `grainMax` 1.
- Studs: `steelBlue()` (5 shades), white diffuse + metallic specular, **ordered** dither, local-frame shading,
  shared seed → identical studs.

## Plastic colour set

The capacitor is the **archetype for the whole plastic series** — other parts reuse its body/band/stud
material and are just recoloured. Any base colour becomes a 7-shade body ramp via
`PreviewTextures.ramp(base)` (HSV: darker+richer lows → base at index 3 → brighter pastel highlight, hue
preserved). Colours were picked from an HSV matrix (hue sweep × a pale→vivid→dark saturation/value set) in the
preview app. Note: brightness variants of a hue should also move **saturation and hue**, not brightness alone
(`PreviewTextures.variant()` — deeper = richer + cooler, brighter = paler + warmer), so a family scatters
across the colour map instead of stacking as one hue.

The chosen set lives in `PlasticColors.SET` (source of truth), one colour per hue — mostly "standard"
(S 0.92, V 0.80), with yellow/blue/violet at "vivid":

| name | H | S | V | | name | H | S | V |
|---|---|---|---|---|---|---|---|---|
| red | 0 | .92 | .80 | | azure | 210 | .92 | .80 |
| orange | 30 | .92 | .80 | | blue | 235 | .85 | .93 |
| yellow | 45 | 1.0 | .93 | | violet | 265 | .85 | .93 |
| lime | 85 | .92 | .80 | | purple | 295 | .92 | .80 |
| teal | 160 | .92 | .80 | | pink | 330 | .92 | .80 |
| cyan | 185 | .92 | .80 | | | | | |

Yellow was pulled warmer (toward red) and saturation-boosted so it reads golden, not lemon.

## Building parts — modeling style

Parts are `PreviewPart` instances distinguished by a `PartType` (the capacitor is the archetype; the switch
is the second). Every part:
- is assembled from `box(...)` calls — axis-aligned boxes with per-face 1:1 lit-palette textures (the shading
  system above), so a new part is just a arrangement of boxes plus which palette each uses;
- **shares the series DNA**: the recolourable plastic body, the white band, and the metallic snap studs (via
  `addStuds`, always identical to each other). Reuse these; don't reinvent the material per part;
- gives each part a distinct silhouette. The capacitor's identity is its wrapping white band; the switch adds
  a raised, metal-framed mechanism. Keep parts distinguishable at a glance.

**The switch (slide switch).** Lime body + full white band + studs, with a centre **slide-switch mechanism**:
a 1px-deep well whose **sides are steel** (a 1px-thick metal fence that stands 1px proud of the top), whose
**floor is a flat 4×2 black plate**, and a **2×2 black stem** rising from that floor to 1px above the fence,
pushed to one end (the slide position). Floor + stem are one black piece.

**Rules that matter when modeling (learned the hard way):**
- **Use common sense on proportions before building.** Work out the pixel steps and make sure they line up —
  well depth, fence height, and the knob stack must be consistent (all 1px steps here). Don't mechanically
  translate numbers that don't physically fit together.
- **Prevent z-fighting: never leave two faces coplanar.** There is no boolean geometry, so stacked/adjacent
  boxes are made to **interpenetrate by a small epsilon (~0.15)** — walls sink into the body, the floor and
  stem overlap into the walls. Coincident faces flicker; a hair of overlap does not.
- **Recessed features (holes) are cut by strips.** A layer with a rectangular hole is built as four border
  boxes around the hole (`layerWithHole`), since geometry can't be subtracted. A shallow well only needs the
  top layer cut — everything below (e.g. the band) stays a full box.
- **Repeated sub-parts must be identical** (the two studs share a seed + local-frame shading).
