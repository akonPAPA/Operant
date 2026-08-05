package com.orderpilot.aibot.domain.aijob;

/**
 * Canonical AiJob lifecycle. First slice uses the transitions required by generation/preview;
 * shortcut states incompatible with this machine are forbidden.
 */
public enum AiJobStatus {
  REQUESTED,
  ADMITTED,
  REJECTED,
  LEASED,
  RUNNING,
  OUTPUT_RECEIVED,
  VALIDATED,
  INVALID,
  STALE,
  SUGGESTION_READY,
  FAILED,
  CANCELLED;

  public boolean isTerminal() {
    return this == REJECTED
        || this == SUGGESTION_READY
        || this == FAILED
        || this == CANCELLED
        || this == INVALID
        || this == STALE;
  }
}
