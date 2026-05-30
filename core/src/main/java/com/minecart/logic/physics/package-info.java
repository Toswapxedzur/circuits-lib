/**
 * Bridges the domain types in {@link com.minecart.logic} (components, edges, nodes, lock state,
 * rigidity flag) into the abstract physics solver in {@code com.minecart.physics}. The adapter is
 * one-directional: domain pose flows in, solver runs over rigid bodies + constraints, then the
 * resulting body poses are written back to the domain's {@link com.minecart.variant.info.PositionInfo}
 * / {@link com.minecart.variant.info.RotationInfo} and replicated via the standard element-change
 * notification path.
 *
 * <h2>What gets mapped</h2>
 * <ul>
 *   <li>Every {@link com.minecart.logic.CircuitComponent} reachable from the seed becomes a
 *       {@link com.minecart.physics.Body}. Inverse-inertias come from
 *       {@link com.minecart.logic.CircuitComponent#effectiveLockState}: FREE → {@code (1,1)},
 *       ORIENTED → {@code (1,0)}, PIVOTED → {@code (0,1)} pivoted at the strict
 *       authored point, LOCKED → {@code (0,0)}.</li>
 *   <li>Every free {@link com.minecart.logic.CircuitNode} (no owning component) reachable from the
 *       seed becomes a {@link com.minecart.physics.Body} too. Nodes have no rotation DOF, so
 *       {@code invMassR=0} unconditionally; {@code invMassT=0} when the node's
 *       {@link com.minecart.variant.info.PositionInfo#isFixed()} flag is set, else {@code 1}.</li>
 *   <li>Every {@link com.minecart.logic.CircuitEdge} reachable from the seed contributes a
 *       {@link com.minecart.physics.constraint.DistanceConstraint} iff its
 *       {@link com.minecart.variant.info.RigidityInfo#isRigid()} flag is set. Flexible edges are
 *       skipped — they don't kinematically couple their endpoints.</li>
 *   <li>Multi-owner port nodes (Phase 2b cascade outcome: a single node shared between several
 *       components' port slots) contribute a star of
 *       {@link com.minecart.physics.constraint.PinJointConstraint}s between the owners' anchor
 *       points so the shared anchor stays geometrically coincident across drags.</li>
 * </ul>
 *
 * <h2>Sub-graph traversal</h2>
 * BFS from the seed across (a) external edges (skipping component-internal struts which the
 * rigid-body model already accounts for via anchor offsets) and (b) multi-owner port memberships.
 * No depth limit — the active set is "everything kinematically reachable". For typical editor
 * workloads this is tens of bodies and tens of constraints per drag tick.
 *
 * <h2>Gesture model</h2>
 * Both translation and rotation gestures are applied by locking the seed body at its post-gesture
 * pose: its inverse inertias are zeroed and the solver propagates the corresponding motion across
 * the constraint graph. Locked / partially-locked bodies elsewhere in the chain naturally refuse
 * to budge along their forbidden DOFs ({@code invMass=0}), so the user's choice that "rotation
 * locks always win" falls out of the inverse-inertia mapping with no special handling.
 *
 * <h2>What this adapter does NOT do</h2>
 * <ul>
 *   <li>Topology mutation. Combine, edge rewire, port swap, delete — all handled separately by
 *       {@link com.minecart.logic.cascade.CombineCascadeEngine} and the dedicated handlers.</li>
 *   <li>Persistence. The adapter operates on the live in-memory state; saved-world serialisation
 *       happens through the existing {@link com.minecart.variant.info.PositionInfo} /
 *       {@link com.minecart.variant.info.RotationInfo} / {@link com.minecart.variant.info.LockInfo}
 *       persistence path.</li>
 *   <li>Compliance / softness. The solver is hard PBD; the rigid bars don't stretch. Soft / XPBD
 *       behaviour is a future-work item, not in scope for the editor's initial cascading-drag
 *       feature.</li>
 * </ul>
 */
package com.minecart.logic.physics;
