// The physics module is intentionally a leaf: it depends on nothing except the test/log facade
// the root build already injects into every subproject. Keeping it dependency-free means callers
// (core, server, future adapters) can pull it in without dragging in any of the circuit-specific
// types — and that the solver can be unit-tested in complete isolation from the rest of the world.

dependencies {
    // No production dependencies. The root build already wires in SLF4J for logging; that's enough.
}
