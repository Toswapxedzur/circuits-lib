# core/src/main/resources — runtime resources bundled into the core jar

## Native libraries (bundled `libngspice`)

The electrical solver is ngspice, loaded via JNA in
[`NgSpice.java`](../java/com/minecart/spice/NgSpice.java). ngspice is the **sole** electrical solver
(the hand-rolled EJML fallback was removed 2026-09-20), so the native library is bundled here to make
the app self-contained — a fresh machine needs no `brew install ngspice`.

### Layout — one folder per platform, named by JNA's `Platform.RESOURCE_PREFIX`
```
<os-arch>/<mapped library name>
```
`NgSpice.loadLibrary()` calls `Native.extractFromResourcePath("ngspice", …)`, which maps `"ngspice"`
to the platform's library name and looks it up under the platform prefix, then extracts it to a temp
file and loads it by absolute path. Load order: `NGSPICE_LIB` env override → **bundled (here)** →
system install. `NgSpice.loadedFrom()` reports which won.

| Platform | Folder | File | Status |
|---|---|---|---|
| macOS Apple Silicon | `darwin-aarch64/` | `libngspice.dylib` | ✅ bundled |
| macOS Intel | `darwin-x86-64/` | `libngspice.dylib` | ⬜ not bundled (drop file in) |
| Linux x86-64 | `linux-x86-64/` | `libngspice.so` | ⬜ |
| Windows x86-64 | `win32-x86-64/` | `ngspice.dll` | ⬜ |

On an un-bundled platform the app falls back to a system libngspice; if none, simulation is disabled.

### Provenance of `darwin-aarch64/libngspice.dylib`
Copied verbatim from Homebrew **libngspice 47**
(`/opt/homebrew/Cellar/libngspice/47/lib/libngspice.0.dylib`), renamed to the unversioned mapped name.
It links only against system libraries (`libSystem`, `libc++` — verified with `otool -L`), so no other
dylibs need to travel with it. It is ad-hoc code-signed (required for arm64); the file is copied
**unmodified** so that signature stays valid — do **not** run `install_name_tool`/`strip` on it (that
would break the signature and the load). To refresh for a new ngspice version, re-copy from Homebrew
the same way.

### To add another platform
Install libngspice there, confirm `otool -L` / `ldd` shows only system deps (else vendor those too),
copy the shared lib into the matching `<os-arch>/` folder under the mapped name, and add a row above.
`NgSpiceBundledTest` auto-covers any platform whose folder is populated.
