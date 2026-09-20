# CircuitsLib architecture standard

The house style. New code follows this; old code converges on it (see the phased roadmap at the end).
The guiding influence is Minecraft's **Create** mod: **single-responsibility classes, composition over
inheritance, behaviour attached to its type, and registries** — never a central `switch`/`instanceof`
that must be edited to add a case.

## The prime directive: behaviour lives with its type, not in a central switch
Adding a new component / element / part must mean **adding a class (and one registry line)**, editing
**zero** central switches. If you find yourself writing `switch (kind)` / a chain of `instanceof Foo`
over a domain family, that behaviour belongs on the type. Two mechanisms:

1. **Polymorphic method on the type** — for intrinsic domain behaviour. Example (target): each
   `CircuitEdge`/`CircuitComponent` overrides `emitSpice(ctx)` to emit its own netlist, instead of
   `SpiceSolver.build` branching on `instanceof Resistor/Battery/…`. The base method is the safe default.
2. **Composable behaviour object** (Create's `BlockEntityBehaviour`) — for cross-cutting, per-type
   concerns that don't belong on the domain class (animation, glow, interaction, board circuit
   contribution). A small class per concern, composed per type via a registry (e.g. a motor part =
   `[SpinBehaviour, HeatGlowBehaviour]`). See `render/engine/behaviour/` (Phase 1).

## Registries: one idiom
Every type family uses the same triad, already the project lingua franca — reuse it, don't invent a
parallel one:
- a **`XRegistry`**: `register(id, …)` (rejects duplicates) + `getType(id)` + optional `freeze()`;
- a typed **`XType`** handle (holds id, factory, per-type strategy maps like action handlers);
- an **`AllXs`** static holder of `public static final XType` fields, triggered via a documented
  `init()`/`<clinit>` bootstrap.
Canonical example: `core/.../registry/{CircuitElementRegistry, CircuitElementType, AllComponents}`.
Mirrored by `ElementInfo*`, `Action*`, `SnapPart*`, `PayloadRegistry`, `TagRegistry`. Behaviour
registries (Phase 1+) follow the same shape.

## Composition over inheritance
- **Attachable data/behaviour** rides the `ElementInfo` seam (`CircuitElement.infos` map +
  `ElementInfoInjectEvent` + `InfoInjectors`) — the Create component analog. Attach data as an
  `ElementInfo`; attach *behaviour* as a behaviour object composed via its registry. Do NOT add a field
  to a domain class for a concern only some types have.
- **Cross-cutting contributions** use parent-chain composition, not Java inheritance: see
  `RenderRegistry`/`RenderElementType`/`RenderContribution` and `InfoPanelRegistry`/`InfoPanelElementType`
  /`InfoPanelFragment`. New rendering/UI/behaviour that layers by element ancestry copies this shape.

## Category polymorphism, not repeated `instanceof`
The Node / Edge / Component trichotomy must not be copy-pasted. Branch on category via a polymorphic
accessor or `accept(visitor)` on `CircuitElement`, not `if (e instanceof CircuitNode) … else if …`
scattered across files.

## Single responsibility & layering
- **One class, one job.** A screen orchestrates; it does not run the server tick, build circuits, host a
  debug console, or render a bespoke HUD. A renderer renders; it does not own persistence, collision, or
  domain circuit-building. Split god classes into named collaborators (e.g. `PlacementStore`,
  `SnapSolver`, `CollisionIndex`, `FocusPicker`, `GhostPreview`, `BoardPersistence`).
- **Domain logic lives in `core`; presentation in `display`.** Circuit topology, simulation, and
  circuit-from-geometry belong in `core` even if a display feature triggers them. Module deps stay
  acyclic: `physics ← core ← protocol ← {client, server} ← display`. Never import up.
- **Methods stay readable** (roughly ≤ ~80 lines); extract phases of a long method into named helpers.
- **Test/debug harness is not production code** — consoles, scripted-input hosts, and self-tests live in
  test sources or clearly-separated dev-only units, not inside screens.

## Datagen = art; code = behaviour
The datagen pipeline (`Parts` → `SeedPartTextures`/`GenModels` → `ModelLoader`/`PartAtlas`) is the source
of truth for **mesh/geometry/textures**. Animation and interaction **logic** are code (behaviour
classes), not data descriptors. Regenerate ahead-of-time and commit BOTH generator and output
(`:display:seedtextures` then `:display:genmodels`).

## Verification
Every change keeps the app runnable and **all module test suites green** before a checkpoint commit.
- Behaviour-preserving refactors: prove same behaviour with tests (e.g. same solves) and grep that the
  replaced switch/dead code has **no surviving references**.
- Interactive/animation behaviour: headless unit tests (HeadlessApplication seam, see
  `PhysicalCircuitMappingTest`) + the live console (`-Pconsole=1`, `display/scripts/live_console.py`).

## Phased convergence roadmap
Tracked in project memory `architecture-refactor-roadmap`. Summary: P0 this doc · P1 snap
`PartBehaviour` system (also ships capacitor-swell + momentary-button animations) · P2 `emitSpice()`
polymorphism · P3 circuit-from-geometry → core · P4 decompose `PhysicalBoardView` · P5 category
polymorphism + drop `SnapModelBridge` `char kind` · P6 decompose screens + extract console/phystest/HUD ·
P7 unify Render/Panel composition kernels, decompose `Parts.java`. Each phase is build-green + committed.
