# CircuitsLib — Kotlin/Gradle library

> 🤖 **AI protocol:** Read `programme/package-info.md` + `../package-info.md` first. Update this file when the project changes. Never delete without owner consent.

- **Type:** Java 21 Gradle multi-module (`physics`, `core`, `protocol`, `client`, `server`, `display`). Snap-Circuits style educational circuit sandbox: 2D + 3D (libGDX) editors over an electrical core (`core/…/logic/ServerCircuit`).
- **Electrical solver (2026-09-05):** ngspice via JNA (`core/…/spice/NgSpice`, `SpiceSolver`) — one adaptive, error-controlled transient per tick, capacitor charge carried as `ic`, so error is bounded per tick and does not grow with time. Needs `libngspice` (`brew install ngspice`; or set `NGSPICE_LIB=<dir>`). Falls back to the built-in EJML linear solver when the library is missing or `-Dcircuitslib.solver=ejml`. Unsupported element types also fall back (only Wire/Resistor/Battery/Capacitor/Diode/BJTransistor are mapped).
- **Git:** `main` → `https://github.com/Toswapxedzur/CircuitsLib.git` (pushed 2026-09-05).
