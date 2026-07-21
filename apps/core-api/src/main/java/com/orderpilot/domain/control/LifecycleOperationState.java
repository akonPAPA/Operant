package com.orderpilot.domain.control;

/**
 * P1-E2A - the closed lifecycle-operation state set for the bounded control slice.
 *
 * <p>Transitions proven in this slice:
 * <pre>
 *   QUEUED  --lease-->  LEASED  --complete(SUCCEEDED)-->  SUCCEEDED
 *                       LEASED  --complete(FAILED)   -->  FAILED
 *   (LEASED|RUNNING and lease expired)  --lease-->  LEASED   (re-lease, fencing token increments)
 * </pre>
 *
 * <p>{@link #RUNNING} is a valid in-flight state reserved for the deferred progress/heartbeat slice; a
 * completion is accepted from either {@link #LEASED} or {@link #RUNNING}. There is intentionally no
 * CANCELLED state: safe cancellation semantics are out of scope here and are not claimed.
 */
public enum LifecycleOperationState {
  QUEUED,
  LEASED,
  RUNNING,
  SUCCEEDED,
  FAILED;

  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED;
  }

  public boolean isInFlight() {
    return this == LEASED || this == RUNNING;
  }
}
