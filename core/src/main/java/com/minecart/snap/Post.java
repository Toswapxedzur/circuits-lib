package com.minecart.snap;

/**
 * A connection point on the baseboard: an integer lattice point {@code (col, row)} on a given
 * {@code layer}. Posts are where snap parts join electrically — two parts whose pins land on the same
 * post share the same derived {@link com.minecart.logic.CircuitNode}, which is exactly how snapping two
 * parts together wires them in the real toy. Value semantics (record) make it a safe map key for the
 * post→node table in {@link PostGrid}.
 */
public record Post(int col, int row, int layer) {
}
