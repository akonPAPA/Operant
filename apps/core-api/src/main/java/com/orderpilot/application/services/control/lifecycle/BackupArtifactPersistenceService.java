package com.orderpilot.application.services.control.lifecycle;

import com.orderpilot.domain.control.BackupArtifact;
import com.orderpilot.domain.control.BackupArtifact.AvailableMetadata;
import com.orderpilot.domain.control.BackupArtifactRepository;
import com.orderpilot.domain.control.BackupArtifactState;
import com.orderpilot.domain.control.LifecycleOperation;
import com.orderpilot.domain.control.LifecycleOperationRepository;
import com.orderpilot.domain.control.LifecycleOperationResultCode;
import com.orderpilot.domain.control.LifecycleOperationType;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P1-E2B-02 internal persistence seam for backup artifact authority. It creates no files, performs no
 * encryption, and starts no process; it only coordinates database artifact rows, lifecycle state, and
 * durable lifecycle audit in one transaction boundary.
 */
@Service
public class BackupArtifactPersistenceService {
  private final BackupArtifactRepository artifactRepository;
  private final LifecycleOperationRepository operationRepository;
  private final LifecycleBackupOperationService lifecycleOperationService;
  private final LifecycleOperationAuditor auditor;
  private final Clock clock;

  public BackupArtifactPersistenceService(
      BackupArtifactRepository artifactRepository,
      LifecycleOperationRepository operationRepository,
      LifecycleBackupOperationService lifecycleOperationService,
      LifecycleOperationAuditor auditor,
      Clock clock) {
    this.artifactRepository = artifactRepository;
    this.operationRepository = operationRepository;
    this.lifecycleOperationService = lifecycleOperationService;
    this.auditor = auditor;
    this.clock = clock;
  }

  @Transactional
  public BackupArtifact stageArtifact(StageArtifactCommand command) {
    StageArtifactCommand safe = Objects.requireNonNull(command, "command");
    String executor = requirePrincipalFingerprint(safe.executorFingerprint());
    Instant now = clock.instant();
    LifecycleOperation operation = operationRepository.findWithLockByPublicId(safe.operationPublicId())
        .orElseThrow(LifecycleControlException.OperationNotFound::new);
    requireCurrentExecution(operation, executor, safe.fencingToken(), now);

    BackupArtifact artifact = BackupArtifact.staged(
        safe.artifactPublicHandle(),
        operation,
        BackupArtifact.POSTGRES_CUSTOM_FORMAT,
        safe.storageKey(),
        operation.getAttempt(),
        operation.getFencingToken(),
        now);
    artifactRepository.save(artifact);
    auditor.artifactStaged(operation, artifact, executor);
    return artifact;
  }

  @Transactional
  public BackupArtifact makeArtifactAvailableAndComplete(FinalizeAvailableCommand command) {
    FinalizeAvailableCommand safe = Objects.requireNonNull(command, "command");
    String executor = requirePrincipalFingerprint(safe.executorFingerprint());
    Instant now = clock.instant();
    LifecycleOperation operation = operationRepository.findWithLockByPublicId(safe.operationPublicId())
        .orElseThrow(LifecycleControlException.OperationNotFound::new);
    requireCurrentExecution(operation, executor, safe.fencingToken(), now);

    BackupArtifact artifact = artifactRepository.findWithLockByPublicHandle(safe.artifactPublicHandle())
        .orElseThrow(() -> new LifecycleControlException.InvalidRequest("BACKUP_ARTIFACT_NOT_FOUND"));
    requireArtifactCurrentForOperation(operation, artifact);

    artifact.markAvailable(safe.metadata(), now);
    artifactRepository.save(artifact);
    auditor.artifactAvailable(operation, artifact, executor);
    lifecycleOperationService.complete(
        executor,
        operation.getPublicId(),
        safe.fencingToken(),
        LifecycleOperationResultCode.BACKUP_COMPLETED);
    return artifact;
  }

  @Transactional
  public LifecycleOperation failOperation(FinalizeFailureCommand command) {
    FinalizeFailureCommand safe = Objects.requireNonNull(command, "command");
    String executor = requirePrincipalFingerprint(safe.executorFingerprint());
    LifecycleOperationResultCode resultCode = Objects.requireNonNull(safe.resultCode(), "resultCode");
    if (resultCode.terminalState() != com.orderpilot.domain.control.LifecycleOperationState.FAILED) {
      throw new LifecycleControlException.InvalidRequest("BACKUP_FAILURE_RESULT_REQUIRED");
    }

    Instant now = clock.instant();
    LifecycleOperation operation = operationRepository.findWithLockByPublicId(safe.operationPublicId())
        .orElseThrow(LifecycleControlException.OperationNotFound::new);
    requireCurrentExecution(operation, executor, safe.fencingToken(), now);

    if (safe.artifactPublicHandle() != null && !safe.artifactPublicHandle().isBlank()) {
      BackupArtifact artifact = artifactRepository.findWithLockByPublicHandle(safe.artifactPublicHandle())
          .orElseThrow(() -> new LifecycleControlException.InvalidRequest("BACKUP_ARTIFACT_NOT_FOUND"));
      requireArtifactCurrentForOperation(operation, artifact);
      if (artifact.getState() == BackupArtifactState.STAGED) {
        artifact.reject(safe.artifactFailureCode(), now);
        artifactRepository.save(artifact);
        auditor.artifactRejected(operation, artifact, executor, safe.artifactFailureCode());
      }
    }

    return lifecycleOperationService.complete(
        executor,
        operation.getPublicId(),
        safe.fencingToken(),
        resultCode);
  }

  private static void requireCurrentExecution(
      LifecycleOperation operation, String executorFingerprint, long fencingToken, Instant now) {
    if (operation.getOperationType() != LifecycleOperationType.BACKUP) {
      throw new LifecycleControlException.InvalidRequest("BACKUP_OPERATION_REQUIRED");
    }
    if (!operation.getState().isInFlight()) {
      throw new LifecycleControlException.CompletionConflict();
    }
    if (!executorFingerprint.equals(operation.getLeasedByFingerprint())) {
      throw new LifecycleControlException.WrongExecutor();
    }
    if (operation.getFencingToken() == null || operation.getFencingToken() != fencingToken) {
      throw new LifecycleControlException.StaleFencingToken();
    }
    if (operation.getLeaseExpiresAt() == null || !now.isBefore(operation.getLeaseExpiresAt())) {
      throw new LifecycleControlException.LeaseExpired();
    }
  }

  private static void requireArtifactCurrentForOperation(
      LifecycleOperation operation, BackupArtifact artifact) {
    if (!operation.getId().equals(artifact.getLifecycleOperation().getId())) {
      throw new LifecycleControlException.InvalidRequest("BACKUP_ARTIFACT_OPERATION_MISMATCH");
    }
    if (!Objects.equals(artifact.getExecutionAttempt(), operation.getAttempt())
        || !Objects.equals(artifact.getFencingToken(), operation.getFencingToken())) {
      throw new LifecycleControlException.StaleFencingToken();
    }
  }

  private static String requirePrincipalFingerprint(String value) {
    if (value == null || value.isBlank() || "unknown".equals(value)) {
      throw new LifecycleControlException.InvalidRequest("CONTROL_PRINCIPAL_REQUIRED");
    }
    return value;
  }

  public record StageArtifactCommand(
      String operationPublicId,
      String executorFingerprint,
      long fencingToken,
      String artifactPublicHandle,
      String storageKey) {}

  public record FinalizeAvailableCommand(
      String operationPublicId,
      String executorFingerprint,
      long fencingToken,
      String artifactPublicHandle,
      AvailableMetadata metadata) {}

  public record FinalizeFailureCommand(
      String operationPublicId,
      String executorFingerprint,
      long fencingToken,
      String artifactPublicHandle,
      LifecycleOperationResultCode resultCode,
      String artifactFailureCode) {}
}
