package com.orderpilot.application.services.control.lifecycle;

import com.orderpilot.domain.control.LifecycleOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * P1-E2A - emits one bounded structured record per sensitive lifecycle-operation event. Following the
 * merged control-plane precedent ({@code OperationalEventAccessAuditor}), it uses a dedicated audit
 * logger namespace and records only bounded, non-reversible facts: the opaque operation id, the fixed
 * operation type, state, attempt count, bounded result code, and already-hashed principal fingerprints.
 *
 * <p>It NEVER records a credential, key material, raw request body, idempotency key, fencing token,
 * signature, stack trace, stdout, stderr, filesystem path, or environment value. A durable/persisted
 * control-operation audit store is out of scope for this slice and remains NOT_PROVEN.
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
            + "executorFingerprint={}",
        operation.getPublicId(),
        operation.getOperationType(),
        operation.getState(),
        operation.getAttempt(),
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
            + "state={} executorFingerprint={}",
        operation.getPublicId(),
        operation.getOperationType(),
        operation.getState(),
        executorFingerprint);
  }

  public void wrongExecutorReportDenied(
      LifecycleOperation operation, String executorFingerprint) {
    auditLogger.warn(
        "lifecycle-operation event=WRONG_EXECUTOR_REPORT_DENIED operationId={} operationType={} "
            + "state={} executorFingerprint={}",
        operation.getPublicId(),
        operation.getOperationType(),
        operation.getState(),
        executorFingerprint);
  }

  public void expiredLeaseReportDenied(
      LifecycleOperation operation, String executorFingerprint) {
    auditLogger.warn(
        "lifecycle-operation event=EXPIRED_LEASE_REPORT_DENIED operationId={} operationType={} "
            + "state={} executorFingerprint={}",
        operation.getPublicId(),
        operation.getOperationType(),
        operation.getState(),
        executorFingerprint);
  }
}
