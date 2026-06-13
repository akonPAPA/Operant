package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-16F Runtime Unit Estimation — cheap, O(1), side-effect-free estimate of an operation's work
 * size in usage units.
 *
 * <p>Contract: always returns {@code >= 1}; never throws for missing/garbage metadata (returns 1);
 * performs no external I/O, no repository scans, no object-storage reads, and no AI/provider calls;
 * clamps unreasonable values and saturates on overflow.
 */
public interface RuntimeUnitEstimator {

  /** Estimate the usage units for the request. Always returns at least 1. */
  int estimate(RuntimeUnitEstimateRequest request);
}
