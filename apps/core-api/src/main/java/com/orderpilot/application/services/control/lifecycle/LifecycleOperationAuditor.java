package com.orderpilot.application.services.control.lifecycle;

import com.orderpilot.domain.control.BackupArtifact;
import com.orderpilot.domain.control.LifecycleOperation;
import com.orderpilot.domain.control.LifecycleOperationAudit;
import com.orderpilot.domain.control.LifecycleOperationAuditEventType;
import com.orderpilot.domain.control.LifecycleOperationAuditPrincipalType;
import com.orderpilot.domain.control.LifecycleOperationAuditRepository;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * P1-E2B - emits bounded lifecycle audit facts to the dedicated audit logger and, when running as a
 * Spring bean, appends them to the deployment-global lifecycle audit table in the caller's transaction.
 * It records only opaque operation/artifact handles, bounded result codes, and already-hashed principal
 * fingerprints. It never records credentials, key material, raw request bodies, idempotency keys, fencing
 * tokens, command lines, paths, stdout/stderr, stack traces, environment values, or customer data.
 */
@Component
public class LifecycleOperationAuditor {
  public static final String AUDIT_LOGGER_NAME =
      "com.orderpilot.security.control.audit.LifecycleOperation";

  private final Logger auditLogger;
  private final LifecycleOperationAuditRepository auditRepository;
  private final Clock clock;

  @Autowired
  public LifecycleOperationAuditor(LifecycleOperationAuditRepository auditRepository, Clock clock) {
    this(LoggerFactory.getLogger(AUDIT_LOGGER_NAME), auditRepository, clock);
  }

  LifecycleOperationAuditor(Logger auditLogger) {
    this(auditLogger, null, Clock.systemUTC());
  }

  LifecycleOperationAuditor(
      Logger auditLogger, LifecycleOperationAuditRepository auditRepository, Clock clock) {
    this.auditLogger = Objects.requireNonNull(auditLogger, "auditLogger");
    this.auditRepository = auditRepository;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public void backupRequested(LifecycleOperation operation, String requestedByFingerprint) {
    auditLogger.info(
        "lifecycle-operation event=BACKUP_REQUESTED operationId={} operationType={} state={} "
            + "principalFingerprint={}",
        operation.getPublicId(),
        operation.getOperationType(),
        operation.getState(),
        requestedByFingerprint);
    append(operation, null, LifecycleOperationAuditEventType.BACKUP_REQUESTED,
        LifecycleOperationAuditPrincipalType.STAFF, requestedByFingerprint, null, "{}");
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
    append(operation, null, LifecycleOperationAuditEventType.BACKUP_LEASE_ACQUIRED,
        LifecycleOperationAuditPrincipalType.EXECUTOR, executorFingerprint, null,
        "{\"attempt\":" + operation.getAttempt() + "}");
  }

  public void artifactStaged(
      LifecycleOperation operation, BackupArtifact artifact, String executorFingerprint) {
    auditLogger.info(
        "lifecycle-operation event=BACKUP_ARTIFACT_STAGED operationId={} artifactHandle={} state={} "
            + "executorFingerprint={}",
        operation.getPublicId(),
        artifact.getPublicHandle(),
        artifact.getState(),
        executorFingerprint);
    append(operation, artifact, LifecycleOperationAuditEventType.BACKUP_ARTIFACT_STAGED,
        LifecycleOperationAuditPrincipalType.EXECUTOR, executorFingerprint, null,
        "{\"artifactHandle\":\"" + artifact.getPublicHandle() + "\"}");
  }

  public void artifactAvailable(
      LifecycleOperation operation, BackupArtifact artifact, String executorFingerprint) {
    auditLogger.info(
        "lifecycle-operation event=BACKUP_ARTIFACT_AVAILABLE operationId={} artifactHandle={} "
            + "state={} executorFingerprint={}",
        operation.getPublicId(),
        artifact.getPublicHandle(),
        artifact.getState(),
        executorFingerprint);
    append(operation, artifact, LifecycleOperationAuditEventType.BACKUP_ARTIFACT_AVAILABLE,
        LifecycleOperationAuditPrincipalType.EXECUTOR, executorFingerprint, null,
        "{\"artifactHandle\":\"" + artifact.getPublicHandle() + "\"}");
  }

  public void artifactRejected(
      LifecycleOperation operation, BackupArtifact artifact, String executorFingerprint, String failureCode) {
    auditLogger.info(
        "lifecycle-operation event=BACKUP_ARTIFACT_REJECTED operationId={} artifactHandle={} "
            + "state={} failureCode={} executorFingerprint={}",
        operation.getPublicId(),
        artifact.getPublicHandle(),
        artifact.getState(),
        failureCode,
        executorFingerprint);
    append(operation, artifact, LifecycleOperationAuditEventType.BACKUP_ARTIFACT_REJECTED,
        LifecycleOperationAuditPrincipalType.EXECUTOR, executorFingerprint, failureCode,
        "{\"artifactHandle\":\"" + artifact.getPublicHandle() + "\"}");
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
    append(operation, null, LifecycleOperationAuditEventType.BACKUP_SUCCEEDED,
        LifecycleOperationAuditPrincipalType.EXECUTOR, executorFingerprint,
        operation.getResultCode().name(), "{}");
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
    append(operation, null, LifecycleOperationAuditEventType.BACKUP_FAILED,
        LifecycleOperationAuditPrincipalType.EXECUTOR, executorFingerprint,
        operation.getResultCode().name(), "{}");
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

  private void append(
      LifecycleOperation operation,
      BackupArtifact artifact,
      LifecycleOperationAuditEventType eventType,
      LifecycleOperationAuditPrincipalType principalType,
      String principalFingerprint,
      String resultCode,
      String metadata) {
    if (auditRepository == null) {
      return;
    }
    auditRepository.save(new LifecycleOperationAudit(
        operation,
        artifact,
        eventType,
        principalType,
        principalFingerprint,
        resultCode,
        metadata,
        clock.instant()));
  }
}
