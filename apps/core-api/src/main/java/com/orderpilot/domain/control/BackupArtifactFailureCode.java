package com.orderpilot.domain.control;

/** Closed, bounded reasons why a staged backup artifact did not become authoritative. */
public enum BackupArtifactFailureCode {
  BACKUP_FAILED_PREFLIGHT,
  BACKUP_FAILED_EXECUTION,
  BACKUP_TIMED_OUT,
  EXPIRED_LEASE_REPLACED;

  public static BackupArtifactFailureCode fromResultCode(LifecycleOperationResultCode resultCode) {
    return switch (resultCode) {
      case BACKUP_FAILED_PREFLIGHT -> BACKUP_FAILED_PREFLIGHT;
      case BACKUP_FAILED_EXECUTION -> BACKUP_FAILED_EXECUTION;
      case BACKUP_TIMED_OUT -> BACKUP_TIMED_OUT;
      case BACKUP_COMPLETED -> throw new IllegalArgumentException("BACKUP_FAILURE_RESULT_REQUIRED");
    };
  }
}
