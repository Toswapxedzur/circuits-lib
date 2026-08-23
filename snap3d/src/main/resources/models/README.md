# Nature models (drop CC0 GLB/glTF here)

Drop low-poly **CC0** nature models into this folder and the 3D scene picks them up
automatically on launch (`loadNatureModels()` scans this directory):

- Files with **rock / stone / bush / grass / log / mushroom / flower / plant / fern / stump**
  in the name are scattered as **ground props**.
- Everything else (`*tree*`, `*pine*`, …) is scattered as **trees**.
- Supported: `.glb`, `.gltf` (with jme3-plugins), `.j3o`.

If this folder has no models, the scene falls back to procedural cone trees.

## Recommended pack (CC0, low-poly, glTF)

**Quaternius — Ultimate Stylized Nature Pack** (60+ trees/rocks/bushes/grass):
<https://quaternius.com/packs/ultimatestylizednature.html>
(also on <https://poly.pizza/bundle/Ultimate-Stylized-Nature-Pack-zyIyYd9yGr>)

Download the glTF version, unzip, and copy the `.gltf`/`.glb` (and their textures) here.

Alternatives: **Kenney Nature Kit** (<https://kenney.nl/assets/nature-kit>), **Poly Pizza** (<https://poly.pizza>).
