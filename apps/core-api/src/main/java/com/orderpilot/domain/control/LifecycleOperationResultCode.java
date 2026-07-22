package com.orderpilot.domain.control;

import java.util.Optional;

/**
 * P1-E2A - the closed, bounded terminal result-code vocabulary an executor may report. This exists so a
 * terminal outcome is a small enumerated code, never a raw log line, exception message, stdout/stderr
 * fragment, filesystem path, or environment value. Each code is consistent with exactly one terminal
 * state ({@link LifecycleOperationState#SUCCEEDED} or {@link LifecycleOperationState#FAILED}).
 */
public enum LifecycleOperationResultCode {
  /** The bounded control slice recorded a successful backup operation (no real artifact in this slice). */
  BACKUP_COMPLETED(LifecycleOperationState.SUCCEEDED),
  /** The operation failed a preflight check before doing work. */
  BACKUP_FAILED_PREFLIGHT(LifecycleOperationState.FAILED),
  /** The operation failed during execution. */
  BACKUP_FAILED_EXECUTION(LifecycleOperationState.FAILED),
  /** The operation exceeded its bounded deadline. */
  BACKUP_TIMED_OUT(LifecycleOperationState.FAILED);

  private final LifecycleOperationState terminalState;

  LifecycleOperationResultCode(LifecycleOperationState terminalState) {
    this.terminalState = terminalState;
  }

  public LifecycleOperationState terminalState() {
    return terminalState;
  }

  public boolean isConsistentWith(LifecycleOperationState state) {
    return terminalState == state;
  }

  /** Strict parse of a client-supplied code; empty for anything outside the closed vocabulary. */
  public static Optional<LifecycleOperationResultCode> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    for (LifecycleOperationResultCode code : values()) {
      if (code.name().equals(raw)) {
        return Optional.of(code);
      }
    }
    return Optional.empty();
  }
}
