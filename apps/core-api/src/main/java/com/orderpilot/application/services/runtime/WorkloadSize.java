package com.orderpilot.application.services.runtime;

/** Deterministic size bucket for an AI workload, derived from estimated input units. */
public enum WorkloadSize {
  SMALL,
  MEDIUM,
  LARGE,
  BULK
}
