package com.orderpilot.domain.control;

/** Closed backup-artifact authority states. Only AVAILABLE is authoritative. */
public enum BackupArtifactState {
  STAGED,
  AVAILABLE,
  REJECTED,
  ORPHANED;

  public boolean isAuthoritative() {
    return this == AVAILABLE;
  }
}
