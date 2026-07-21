package com.orderpilot.application.services.control.lifecycle;

import com.orderpilot.domain.control.LifecycleOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * P1-E2A - emits one bounded structured record per sensitive lifecycle-operation event. Following the
 * merged control-plane precedent ({@code OperationalEventAccessAuditor}), it uses a dedicated audit
 * logger namespace and records only bounded, non-reversible facts: the opaque operation id, the fixed
 * operation type, the state, a bounded result code, and the already-hashed principal fingerprints.
 *
 * <p>It NEVER records a credential, raw request body, stack trace, stdout, stderr, filesystem path,
 * environment value, idempotency key, or fencing secret. A durable/persisted control-operation audit
 * store is out of scope for this slice and remains NOT_PROVEN.
 */
@Component
public class LifecycleOperationAuditor {
  public static final String AUDIT_LOGGER_NAME =
      "com.orderpilot.security.control.audit.LifecycleOperation";

  private final Logger auditLogger;

  public LifecycleOperationAuditor() {
    this(LoggerFactory.getLogger(AUDIT_LOGGER_NAME));
  }

  LifecycleOperationAuditor(Logger auditLogger) {
    this.auditLogger = auditLogger;
  }

  public void backupRequested(LifecycleOperation operation, String requestedByFingerprint) {
    auditLogger.info(
        "lifecycle-operation event=BACKUP_REQUESTED operationId={} operationType={} state={} "
            + "principalFingerprint={}",
        operation.getPublicId(),
        operation.getOperationType(),
        operation.getState(),
        requestedByFingerprint);
  }

  public void leaseAcquired(LifecycleOperation operation, String executorFingerprint) {
    auditLogger.info(
        "lifecycle-operation event=LEASE_ACQUIRED operationId={} operationType={} state={} attempt={} "
            + "fencingToken={} executorFingerprint={}",
        operation.getPublicId(),
        operation.getOperationType(),
        operation.getState(),
        operation.getAttempt(),
        operation.getFencingToken(),
        executorFingerprint);
  }

  public void operationSucceeded(LifecycleOperation operation, String executorFingerprint) {
    auditLogger.info(
        "lifecycle-operation event=OPERATION_SUCCEEDED operationId={} operationType={} state={} "
            + "resultCode={} executorFingerprint={}",
        operation.getPublicId(),
        operation.getOperationType(),
        operation.getState(),
        operation.getResultCode(),
        executorFingerprint);
  }

  public void operationFailed(LifecycleOperation operation, String executorFingerprint) {
    auditLogger.info(
        "lifecycle-operation event=OPERATION_FAILED operationId={} operationType={} state={} "
            + "resultCode={} executorFingerprint={}",
        operation.getPublicId(),
        operation.getOperationType(),
        operation.getState(),
        operation.getResultCode(),
        executorFingerprint);
  }

  public void staleExecutorReportDenied(
      LifecycleOperation operation, long presentedFencingToken, String executorFingerprint) {
    auditLogger.warn(
        "lifecycle-operation event=STALE_EXECUTOR_REPORT_DENIED operationId={} operationType={} "
            + "state={} currentFencingToken={} presentedFencingToken={} executorFingerprint={}",
        operation.getPublicId(),
        operation.getOperationType(),
        operation.getState(),
        operation.getFencingToken(),
        presentedFencingToken,
        executorFingerprint);
  }
}
