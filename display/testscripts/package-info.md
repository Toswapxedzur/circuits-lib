# display/testscripts — scripted-input harness scripts

Deterministic, in-process input tests for the 3D snap editor. Each file is a script for
`com.minecart.display.snap.InputScript`: synthetic key/mouse/wheel/camera events are fed through the SAME
handlers real input hits, the real frame loop runs, named state probes are asserted, and the JVM exits
non-zero on any failure — so the gradle run IS the verdict (no human at the mouse, no screenshots).

Run one:  `./gradlew :display:runsnap -Pworld=snap3d -Pfixedcam=1 -Pinputtest=testscripts/wheel.txt`
(from `display/` is the working dir; `-Pfixedcam=1` keeps the camera deterministic — scripts turn it with `look`).
Grammar + probes: see the `InputScript` javadoc. The general method is recorded in `~/Desktop/AGENTS.md`.

| Script | Verifies |
|---|---|
| `wheel.txt` | wheel→ghost rotation: 15 units/turn, ≥250 ms between turns, no inertia after a burst |
| `deck.txt`  | ←/→ deck selection glides (eased fan), E-panel open/close, held card tracks selection |
| `drag.txt`  | drag-handle: grab the switch knob, free-look, knob follows the crosshair and closes the switch |

Tests never save into the world file (`finish` hard-exits before dispose).
