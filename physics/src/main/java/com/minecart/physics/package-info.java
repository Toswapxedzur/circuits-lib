/**
 * Standalone, dependency-free constraint solver for 2D rigid bodies. Drives drag / rotate
 * gestures and other kinematic edits in the editor by treating elements as rigid bodies with
 * configurable per-axis inverse inertias and resolving inter-body constraints via Position-Based
 * Dynamics (PBD) with Gauss-Seidel iteration.
 *
 * <h2>Design intent</h2>
 * The module is deliberately disconnected from {@code core} (and the rest of the codebase): every
 * type here is generic and parameterised only by primitive doubles plus stable identifiers. An
 * adapter layer in another module maps domain entities (components, edges, nodes) to {@link
 * com.minecart.physics.Body} / {@link com.minecart.physics.constraint.Constraint} instances and
 * commits the solver's output back. This keeps the solver reusable, testable in isolation, and
 * decoupled from the circuit data model.
 *
 * <h2>Architecture</h2>
 * <ul>
 *   <li>{@link com.minecart.physics.Vec2} — immutable 2D vector arithmetic.</li>
 *   <li>{@link com.minecart.physics.Body} — a rigid body with pose {@code (x, y, theta)} plus
 *       {@code invMassT} and {@code invMassR} for per-axis lock control.</li>
 *   <li>{@link com.minecart.physics.AnchorPoint} — a constant body-local offset that resolves to a
 *       world-space point via the body's current pose; used as the endpoint of constraints.</li>
 *   <li>{@link com.minecart.physics.constraint.Constraint} — the projection interface; each
 *       implementation knows how to nudge its endpoint bodies toward satisfaction in a single
 *       iteration and report its current residual.</li>
 *   <li>{@link com.minecart.physics.constraint.DistanceConstraint} — keeps two anchor points at a
 *       fixed distance; models a rigid bar / fixed-length wire.</li>
 *   <li>{@link com.minecart.physics.constraint.PinJointConstraint} — keeps two anchor points
 *       coincident; models a shared node between two bodies.</li>
 *   <li>{@link com.minecart.physics.Solver} — Gauss-Seidel projection loop with deterministic
 *       constraint ordering, configurable iteration count, and convergence tolerance.</li>
 * </ul>
 *
 * <h2>Determinism</h2>
 * The solver applies constraints in their declaration order; callers that need cross-run / cross-
 * client reproducibility should pass the constraint list sorted by some stable key. The solver
 * itself performs no internal reordering or randomisation.
 *
 * <h2>What this module deliberately does NOT do</h2>
 * <ul>
 *   <li>Collision detection / response.</li>
 *   <li>Velocity / impulse / gravity / continuous-time integration. Bodies are positional only;
 *       all motion is driven externally by the caller as "set this anchor to this world point".</li>
 *   <li>Any adapter from circuit-domain types (components, edges, nodes) — that lives in {@code
 *       core} or a future adapter module.</li>
 * </ul>
 */
package com.minecart.physics;
