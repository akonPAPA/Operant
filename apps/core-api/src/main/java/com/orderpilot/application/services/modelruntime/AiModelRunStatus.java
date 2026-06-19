package com.orderpilot.application.services.modelruntime;

/** Safe metadata status for one advisory model run. */
public enum AiModelRunStatus {
  PLANNED,
  SUCCEEDED,
  FAILED,
  SKIPPED,
  REJECTED
}
