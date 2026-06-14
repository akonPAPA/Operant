package com.orderpilot.domain.journey;

/** OP-CAP-22 — lifecycle state of a single milestone. */
public enum MilestoneState {
  NOT_STARTED,
  ACTIVE,
  COMPLETED,
  BLOCKED,
  SKIPPED,
  UNKNOWN
}
