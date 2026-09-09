# display/scripts — dev tools for the display app

| File | Purpose |
|---|---|
| `generate_textures.py` | Texture generation for part models (see the datagen notes in project memory). |
| `live_console.py` | Client for the app's **live console** (`-Pconsole=1` → 127.0.0.1:4711): inspect any object by reflection (`ls`/`get`), write fields (`set`), call methods (`call`), stream values (`watch`), read harness probes, and run InputScript commands (`key`, `look … over`, `scroll`, `expect`) against the RUNNING app. One JSON reply line per command; exit 1 if any command errored. Method: `~/Desktop/AGENTS.md` → "Testing interactive software". |
