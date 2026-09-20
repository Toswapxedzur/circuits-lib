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
| macOS Apple Silicon | `darwin-aarch64/` | `libngspice.dylib` | ✅ bundled (Homebrew v47) |
| macOS Intel | `darwin-x86-64/` | `libngspice.dylib` | ✅ bundled (Homebrew v47) |
| Linux x86-64 | `linux-x86-64/` | `libngspice.so` | ✅ bundled (Ubuntu 24.04 v42) |
| Windows x86-64 | `win32-x86-64/` | `ngspice.dll` | ⬜ not bundled (drop file in) |

On an un-bundled platform the app falls back to a system libngspice; if none, simulation is disabled.

### Provenance & deps (all verified to need only standard system libraries)
- **`darwin-aarch64/libngspice.dylib`** — Homebrew **libngspice 47**
  (`/opt/homebrew/Cellar/libngspice/47/lib/libngspice.0.dylib`), renamed to the unversioned mapped
  name. `otool -L`: only `libSystem` + `libc++`. Ad-hoc code-signed (required for arm64).
- **`darwin-x86-64/libngspice.dylib`** — Homebrew **libngspice 47** bottle (`brew fetch libngspice`,
  extracted from the Intel bottle on the mini, 2026-09-20). `otool -L`: only `libSystem` + `libc++`.
  Its `LC_ID_DYLIB` still carries the `@@HOMEBREW_PREFIX@@` bottle placeholder — harmless, because we
  load by explicit path (`dlopen` ignores `LC_ID`); verified loading + solving on an Intel Mac.
- **`linux-x86-64/libngspice.so`** — Ubuntu 24.04 `libngspice0` **42+ds-3build1**
  (`apt-get download libngspice0` → extracted `libngspice.so.0.0.9`, renamed). `ldd`/`objdump -p`
  NEEDED: only `libm`, `libstdc++`, `libgomp`, `libgcc_s`, `libc` — all standard glibc + gcc runtime.

**Verified with a real solve on each platform (2026-09-20):** a 12 V / 100 Ω / 200 Ω divider gives
`v(2) = 8.0` V — on arm64 via the core test suite, on Intel via a JNA load test on the mini, on Linux
via a Python-`ctypes` `dlopen` test on the VPS (ctypes uses the same `dlopen`/`dlsym` path as JNA).

⛔ Copy each binary **unmodified** — they are (or may be) code-signed and their internal deps are set;
do **not** run `install_name_tool`/`strip`/`patchelf` on them. To refresh for a new ngspice version,
re-fetch the same way (Homebrew bottle for macOS, the distro package for Linux).

### To add another platform
Install libngspice there, confirm `otool -L` / `ldd` shows only system deps (else vendor those too),
copy the shared lib into the matching `<os-arch>/` folder under the mapped name, and add a row above.
`NgSpiceBundledTest` auto-covers any platform whose folder is populated.
