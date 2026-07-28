package com.orderpilot.application.services.control.lifecycle;

import com.orderpilot.domain.control.BackupArtifact;
import com.orderpilot.domain.control.LifecycleOperation;
import com.orderpilot.domain.control.LifecycleOperationAudit;
import com.orderpilot.domain.control.LifecycleOperationAuditEventType;
import com.orderpilot.domain.control.LifecycleOperationAuditPrincipalType;
import com.orderpilot.domain.control.LifecycleOperationAuditRepository;
import com.orderpilot.domain.control.LifecycleOperationRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P1-E2B - emits bounded lifecycle audit facts to the dedicated audit logger and, when running as a
 * Spring bean, appends them to the deployment-global lifecycle audit table. It records only opaque
 * operation/artifact handles, bounded result codes, and already-hashed principal fingerprints.
 */
@Component
public class LifecycleOperationAuditor {
  public static final String AUDIT_LOGGER_NAME =
      "com.orderpilot.security.control.audit.LifecycleOperation";

  /**
   * Fixed backend-owned system principal for internal lifecycle mutations (STAGED -&gt; ORPHANED during
   * re-lease). The re-leasing executor is not the actor of this transition; it is preserved only as
   * bounded {@code triggerExecutorFingerprint} metadata, never as the audit principal fingerprint.
   */
  public static final String SYSTEM_RELEASER_FINGERPRINT = "system:lifecycle-releaser";

  // Bounded charset for values embedded into structured audit metadata JSON. Matches artifact handles
  // (ba_[0-9a-f]{24}) and principal fingerprints; forbids quotes, backslashes, and control characters,
  // so validated values can be quoted directly without arbitrary-string JSON concatenation.
  private static final Pattern SAFE_METADATA_VALUE = Pattern.compile("[0-9A-Za-z:_-]{1,80}");

  private final Logger auditLogger;
  private final LifecycleOperationAuditRepository auditRepository;
  private final LifecycleOperationRepository operationRepository;
  private final TransactionTemplate durableAuditTemplate;
  private final Clock clock;

  @Autowired
  public LifecycleOperationAuditor(
      LifecycleOperationAuditRepository auditRepository,
      LifecycleOperationRepository operationRepository,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this(
        LoggerFactory.getLogger(AUDIT_LOGGER_NAME),
        auditRepository,
        operationRepository,
        transactionManager,
        clock);
  }

  LifecycleOperationAuditor(Logger auditLogger) {
    this(auditLogger, null, null, null, Clock.systemUTC());
  }

  LifecycleOperationAuditor(
      Logger auditLogger,
      LifecycleOperationAuditRepository auditRepository,
      LifecycleOperationRepository operationRepository,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.auditLogger = Objects.requireNonNull(auditLogger, "auditLogger");
    this.auditRepository = auditRepository;
    this.operationRepository = operationRepository;
    this.clock = Objects.requireNonNull(clock, "clock");
    if (transactionManager == null) {
      this.durableAuditTemplate = null;
    } else {
      TransactionTemplate template = new TransactionTemplate(transactionManager);
      template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      this.durableAuditTemplate = template;
    }
  }

  public void backupRequested(LifecycleOperation operation, String requestedByFingerprint) {
    append(operation, null, LifecycleOperationAuditEventType.BACKUP_REQUESTED,
        LifecycleOperationAuditPrincipalType.STAFF, requestedByFingerprint, null, "{}");
    infoAfterCommit(
        "lifecycle-operation event=BACKUP_REQUESTED operationId={} operationType={} state={} principalFingerprint={}",
        operation.getPublicId(),
        operation.getOperationType(),
        operation.getState(),
        requestedByFingerprint);
  }

  public void leaseAcquired(LifecycleOperation operation, String executorFingerprint) {
    append(operation, null, LifecycleOperationAuditEventType.BACKUP_LEASE_ACQUIRED,
        LifecycleOperationAuditPrincipalType.EXECUTOR, executorFingerprint, null,
        "{\"attempt\":" + operation.getAttempt() + "}");
    infoAfterCommit(
        "lifecycle-operation event=LEASE_ACQUIRED operationId={} operationType={} state={} attempt={} executorFingerprint={}",
        operation.getPublicId(),
        operation.getOperationType(),
        operation.getState(),
        operation.getAttempt(),
        executorFingerprint);
  }

  public void artifactStaged(
      LifecycleOperation operation, BackupArtifact artifact, String executorFingerprint) {
    append(operation, artifact, LifecycleOperationAuditEventType.BACKUP_ARTIFACT_STAGED,
        LifecycleOperationAuditPrincipalType.EXECUTOR, executorFingerprint, null,
        artifactMetadata(artifact));
    infoAfterCommit(
        "lifecycle-operation event=BACKUP_ARTIFACT_STAGED operationId={} artifactHandle={} state={} executorFingerprint={}",
        operation.getPublicId(),
        artifact.getPublicHandle(),
        artifact.getState(),
        executorFingerprint);
  }

  public void artifactAvailable(
      LifecycleOperation operation, BackupArtifact artifact, String executorFingerprint) {
    append(operation, artifact, LifecycleOperationAuditEventType.BACKUP_ARTIFACT_AVAILABLE,
        LifecycleOperationAuditPrincipalType.EXECUTOR, executorFingerprint, null,
        artifactMetadata(artifact));
    infoAfterCommit(
        "lifecycle-operation event=BACKUP_ARTIFACT_AVAILABLE operationId={} artifactHandle={} state={} executorFingerprint={}",
        operation.getPublicId(),
        artifact.getPublicHandle(),
        artifact.getState(),
        executorFingerprint);
  }

  public void artifactRejected(
      LifecycleOperation operation,
      BackupArtifact artifact,
      String executorFingerprint,
      String failureCode) {
    append(operation, artifact, LifecycleOperationAuditEventType.BACKUP_ARTIFACT_REJECTED,
        LifecycleOperationAuditPrincipalType.EXECUTOR, executorFingerprint, failureCode,
        artifactMetadata(artifact));
    infoAfterCommit(
        "lifecycle-operation event=BACKUP_ARTIFACT_REJECTED operationId={} artifactHandle={} state={} failureCode={} executorFingerprint={}",
        operation.getPublicId(),
        artifact.getPublicHandle(),
        artifact.getState(),
        failureCode,
        executorFingerprint);
  }

  public void artifactOrphaned(
      LifecycleOperation operation,
      BackupArtifact artifact,
      String triggerExecutorFingerprint,
      String failureCode) {
    append(operation, artifact, LifecycleOperationAuditEventType.BACKUP_ARTIFACT_ORPHANED,
        LifecycleOperationAuditPrincipalType.SYSTEM, SYSTEM_RELEASER_FINGERPRINT, failureCode,
        orphanMetadata(artifact, triggerExecutorFingerprint));
    infoAfterCommit(
        "lifecycle-operation event=BACKUP_ARTIFACT_ORPHANED operationId={} artifactHandle={} state={} failureCode={} systemFingerprint={}",
        operation.getPublicId(),
        artifact.getPublicHandle(),
        artifact.getState(),
        failureCode,
        SYSTEM_RELEASER_FINGERPRINT);
  }

  public void operationSucceeded(LifecycleOperation operation, String executorFingerprint) {
    append(operation, null, LifecycleOperationAuditEventType.BACKUP_SUCCEEDED,
        LifecycleOperationAuditPrincipalType.EXECUTOR, executorFingerprint,
        operation.getResultCode().name(), "{}");
    infoAfterCommit(
        "lifecycle-operation event=OPERATION_SUCCEEDED operationId={} operationType={} state={} resultCode={} executorFingerprint={}",
        operation.getPublicId(),
        operation.getOperationType(),
        operation.getState(),
        operation.getResultCode(),
        executorFingerprint);
  }

  public void operationFailed(LifecycleOperation operation, String executorFingerprint) {
    append(operation, null, LifecycleOperationAuditEventType.BACKUP_FAILED,
        LifecycleOperationAuditPrincipalType.EXECUTOR, executorFingerprint,
        operation.getResultCode().name(), "{}");
    infoAfterCommit(
        "lifecycle-operation event=OPERATION_FAILED operationId={} operationType={} state={} resultCode={} executorFingerprint={}",
        operation.getPublicId(),
        operation.getOperationType(),
        operation.getState(),
        operation.getResultCode(),
        executorFingerprint);
  }

  public void staleExecutorReportDenied(
      LifecycleOperation operation, long presentedFencingToken, String executorFingerprint) {
    appendDurably(operation, LifecycleOperationAuditEventType.BACKUP_STALE_EXECUTOR_DENIED,
        executorFingerprint, "STALE_FENCING_TOKEN", "{\"reason\":\"STALE_FENCING_TOKEN\"}");
    auditLogger.warn(
        "lifecycle-operation event=STALE_EXECUTOR_REPORT_DENIED operationId={} operationType={} state={} executorFingerprint={}",
        operation.getPublicId(),
        operation.getOperationType(),
        operation.getState(),
        executorFingerprint);
  }

  public void wrongExecutorReportDenied(LifecycleOperation operation, String executorFingerprint) {
    appendDurably(operation, LifecycleOperationAuditEventType.BACKUP_WRONG_EXECUTOR_DENIED,
        executorFingerprint, "LIFECYCLE_LEASE_OWNER_MISMATCH",
        "{\"reason\":\"LIFECYCLE_LEASE_OWNER_MISMATCH\"}");
    auditLogger.warn(
        "lifecycle-operation event=WRONG_EXECUTOR_REPORT_DENIED operationId={} operationType={} state={} executorFingerprint={}",
        operation.getPublicId(),
        operation.getOperationType(),
        operation.getState(),
        executorFingerprint);
  }

  public void expiredLeaseReportDenied(LifecycleOperation operation, String executorFingerprint) {
    appendDurably(operation, LifecycleOperationAuditEventType.BACKUP_EXPIRED_LEASE_DENIED,
        executorFingerprint, "LIFECYCLE_LEASE_EXPIRED",
        "{\"reason\":\"LIFECYCLE_LEASE_EXPIRED\"}");
    auditLogger.warn(
        "lifecycle-operation event=EXPIRED_LEASE_REPORT_DENIED operationId={} operationType={} state={} executorFingerprint={}",
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

  private void appendDurably(
      LifecycleOperation operation,
      LifecycleOperationAuditEventType eventType,
      String principalFingerprint,
      String resultCode,
      String metadata) {
    if (auditRepository == null || operationRepository == null || durableAuditTemplate == null) {
      return;
    }
    durableAuditTemplate.executeWithoutResult(status -> {
      LifecycleOperation reloaded = operationRepository.findByPublicId(operation.getPublicId())
          .orElseThrow(LifecycleControlException.OperationNotFound::new);
      append(reloaded, null, eventType, LifecycleOperationAuditPrincipalType.EXECUTOR,
          principalFingerprint, resultCode, metadata);
    });
  }

  private void infoAfterCommit(String pattern, Object... arguments) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      auditLogger.info(pattern, arguments);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        auditLogger.info(pattern, arguments);
      }
    });
  }

  private static String artifactMetadata(BackupArtifact artifact) {
    return "{\"artifactHandle\":" + jsonSafeValue(artifact.getPublicHandle()) + "}";
  }

  private static String orphanMetadata(BackupArtifact artifact, String triggerExecutorFingerprint) {
    return "{\"artifactHandle\":" + jsonSafeValue(artifact.getPublicHandle())
        + ",\"triggerExecutorFingerprint\":" + jsonSafeValue(triggerExecutorFingerprint) + "}";
  }

  private static String jsonSafeValue(String value) {
    if (value == null || !SAFE_METADATA_VALUE.matcher(value).matches()) {
      throw new IllegalArgumentException("AUDIT_METADATA_VALUE_INVALID");
    }
    return "\"" + value + "\"";
  }
}