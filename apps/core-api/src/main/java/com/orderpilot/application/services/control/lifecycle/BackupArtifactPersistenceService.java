package com.orderpilot.application.services.control.lifecycle;

import com.orderpilot.domain.control.BackupArtifact;
import com.orderpilot.domain.control.BackupArtifact.AvailableMetadata;
import com.orderpilot.domain.control.BackupArtifactFailureCode;
import com.orderpilot.domain.control.BackupArtifactRepository;
import com.orderpilot.domain.control.BackupArtifactState;
import com.orderpilot.domain.control.LifecycleOperation;
import com.orderpilot.domain.control.LifecycleOperationRepository;
import com.orderpilot.domain.control.LifecycleOperationResultCode;
import com.orderpilot.domain.control.LifecycleOperationState;
import com.orderpilot.domain.control.LifecycleOperationType;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Internal artifact-aware coordinator for backup terminal authority. It creates no files, performs no
 * encryption, and starts no process; it only coordinates database artifact rows, lifecycle state, and
 * durable lifecycle audit. Backend allocates artifact public identity and canonical storage key.
 */
@Service
public class BackupArtifactPersistenceService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int HANDLE_RANDOM_BYTES = 12;

  private final BackupArtifactRepository artifactRepository;
  private final LifecycleOperationRepository operationRepository;
  private final LifecycleBackupOperationService lifecycleOperationService;
  private final LifecycleOperationAuditor auditor;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  public BackupArtifactPersistenceService(
      BackupArtifactRepository artifactRepository,
      LifecycleOperationRepository operationRepository,
      LifecycleBackupOperationService lifecycleOperationService,
      LifecycleOperationAuditor auditor,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.artifactRepository = artifactRepository;
    this.operationRepository = operationRepository;
    this.lifecycleOperationService = lifecycleOperationService;
    this.auditor = auditor;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public BackupArtifact stageArtifact(StageArtifactCommand command) {
    String executor = requirePrincipalFingerprint(Objects.requireNonNull(command, "command").executorFingerprint());
    try {
      return transactionTemplate.execute(status -> stageArtifactInTransaction(command, executor));
    } catch (DeniedReport denied) {
      denied.audit(auditor);
      throw denied.toPublicException();
    }
  }

  public LifecycleOperation completeReport(FinalizeReportCommand command) {
    FinalizeReportCommand safe = Objects.requireNonNull(command, "command");
    LifecycleOperationResultCode resultCode = Objects.requireNonNull(safe.resultCode(), "resultCode");
    if (resultCode == LifecycleOperationResultCode.BACKUP_COMPLETED) {
      BackupArtifact artifact = makeArtifactAvailableAndComplete(new FinalizeAvailableCommand(
          safe.operationPublicId(),
          safe.executorFingerprint(),
          safe.fencingToken(),
          safe.artifactPublicHandle(),
          safe.storageKey(),
          Objects.requireNonNull(safe.metadata(), "metadata")));
      return artifact.getLifecycleOperation();
    }
    return failOperation(new FinalizeFailureCommand(
        safe.operationPublicId(),
        safe.executorFingerprint(),
        safe.fencingToken(),
        safe.artifactPublicHandle(),
        resultCode));
  }

  public BackupArtifact makeArtifactAvailableAndComplete(FinalizeAvailableCommand command) {
    String executor = requirePrincipalFingerprint(Objects.requireNonNull(command, "command").executorFingerprint());
    try {
      return transactionTemplate.execute(status -> availableInTransaction(command, executor));
    } catch (DeniedReport denied) {
      denied.audit(auditor);
      throw denied.toPublicException();
    }
  }

  public LifecycleOperation failOperation(FinalizeFailureCommand command) {
    String executor = requirePrincipalFingerprint(Objects.requireNonNull(command, "command").executorFingerprint());
    LifecycleOperationResultCode resultCode = Objects.requireNonNull(command.resultCode(), "resultCode");
    if (resultCode.terminalState() != LifecycleOperationState.FAILED) {
      throw new LifecycleControlException.InvalidRequest("BACKUP_FAILURE_RESULT_REQUIRED");
    }
    try {
      return transactionTemplate.execute(status -> failureInTransaction(command, executor, resultCode));
    } catch (DeniedReport denied) {
      denied.audit(auditor);
      throw denied.toPublicException();
    }
  }

  private BackupArtifact stageArtifactInTransaction(StageArtifactCommand command, String executor) {
    Instant now = clock.instant();
    LifecycleOperation operation = lockedOperation(command.operationPublicId());
    requireCurrentExecution(operation, executor, command.fencingToken(), now);

    return artifactRepository.findWithLockByLifecycleOperationIdAndExecutionAttemptAndFencingToken(
            operation.getId(), operation.getAttempt(), operation.getFencingToken())
        .map(existing -> replayOrDenyExactStaged(existing))
        .orElseGet(() -> {
          BackupArtifact artifact = BackupArtifact.staged(
              newPublicHandle(),
              operation,
              BackupArtifact.POSTGRES_CUSTOM_FORMAT,
              canonicalStorageKey(operation),
              operation.getAttempt(),
              operation.getFencingToken(),
              now);
          artifactRepository.save(artifact);
          auditor.artifactStaged(operation, artifact, executor);
          return artifact;
        });
  }

  private BackupArtifact availableInTransaction(FinalizeAvailableCommand command, String executor) {
    Instant now = clock.instant();
    LifecycleOperation operation = lockedOperation(command.operationPublicId());
    if (operation.getState().isTerminal()) {
      requireTerminalReplayBase(
          operation, executor, command.fencingToken(), LifecycleOperationResultCode.BACKUP_COMPLETED);
      BackupArtifact artifact = requireCurrentExecutionArtifact(operation, command.artifactPublicHandle());
      if (artifact.matchesStagedIdentity(
              requireText(command.artifactPublicHandle(), "artifactPublicHandle"),
              requireText(command.storageKey(), "storageKey"))
          && artifact.matchesAvailableMetadata(command.metadata())) {
        return artifact;
      }
      throw new LifecycleControlException.CompletionConflict();
    }

    requireCurrentExecution(operation, executor, command.fencingToken(), now);
    BackupArtifact artifact = requireCurrentExecutionArtifact(operation, command.artifactPublicHandle());
    if (!artifact.matchesStagedIdentity(
        requireText(command.artifactPublicHandle(), "artifactPublicHandle"),
        requireText(command.storageKey(), "storageKey"))) {
      throw new LifecycleControlException.CompletionConflict();
    }
    if (artifact.getState() != BackupArtifactState.STAGED) {
      throw new LifecycleControlException.CompletionConflict();
    }

    artifact.markAvailable(command.metadata(), now);
    artifactRepository.save(artifact);
    auditor.artifactAvailable(operation, artifact, executor);
    lifecycleOperationService.complete(
        executor,
        operation.getPublicId(),
        command.fencingToken(),
        LifecycleOperationResultCode.BACKUP_COMPLETED);
    return artifact;
  }

  private LifecycleOperation failureInTransaction(
      FinalizeFailureCommand command, String executor, LifecycleOperationResultCode resultCode) {
    Instant now = clock.instant();
    LifecycleOperation operation = lockedOperation(command.operationPublicId());
    BackupArtifactFailureCode failureCode = BackupArtifactFailureCode.fromResultCode(resultCode);

    if (operation.getState().isTerminal()) {
      requireTerminalReplayBase(operation, executor, command.fencingToken(), resultCode);
      BackupArtifact artifact = resolveCurrentArtifactForFailure(operation, command.artifactPublicHandle());
      if (artifact == null) {
        return operation;
      }
      if (artifact.getState() == BackupArtifactState.REJECTED
          && failureCode.name().equals(artifact.getFailureCode())) {
        return operation;
      }
      throw new LifecycleControlException.CompletionConflict();
    }

    requireCurrentExecution(operation, executor, command.fencingToken(), now);
    BackupArtifact artifact = resolveCurrentArtifactForFailure(operation, command.artifactPublicHandle());
    if (artifact != null && artifact.getState() == BackupArtifactState.STAGED) {
      artifact.reject(failureCode, now);
      artifactRepository.save(artifact);
      auditor.artifactRejected(operation, artifact, executor, failureCode.name());
    } else if (artifact != null && artifact.getState() != BackupArtifactState.REJECTED) {
      throw new LifecycleControlException.CompletionConflict();
    }

    return lifecycleOperationService.complete(executor, operation.getPublicId(), command.fencingToken(), resultCode);
  }

  private LifecycleOperation lockedOperation(String publicId) {
    return operationRepository.findWithLockByPublicId(publicId)
        .orElseThrow(LifecycleControlException.OperationNotFound::new);
  }

  private BackupArtifact replayOrDenyExactStaged(BackupArtifact existing) {
    if (existing.getState() == BackupArtifactState.STAGED) {
      return existing;
    }
    throw new LifecycleControlException.CompletionConflict();
  }

  private BackupArtifact requireCurrentExecutionArtifact(LifecycleOperation operation, String artifactHandle) {
    BackupArtifact artifact = artifactRepository
        .findWithLockByLifecycleOperationIdAndExecutionAttemptAndFencingToken(
            operation.getId(), operation.getAttempt(), operation.getFencingToken())
        .orElseThrow(LifecycleControlException.CompletionConflict::new);
    if (artifactHandle != null
        && !artifactHandle.isBlank()
        && !artifactHandle.equals(artifact.getPublicHandle())) {
      throw new LifecycleControlException.CompletionConflict();
    }
    return artifact;
  }

  private BackupArtifact resolveCurrentArtifactForFailure(LifecycleOperation operation, String artifactHandle) {
    if (artifactHandle == null || artifactHandle.isBlank()) {
      return artifactRepository.findWithLockByLifecycleOperationIdAndExecutionAttemptAndFencingToken(
          operation.getId(), operation.getAttempt(), operation.getFencingToken()).orElse(null);
    }
    return artifactRepository
        .findWithLockByLifecycleOperationIdAndExecutionAttemptAndFencingTokenAndPublicHandle(
            operation.getId(), operation.getAttempt(), operation.getFencingToken(), artifactHandle)
        .orElseThrow(LifecycleControlException.CompletionConflict::new);
  }

  private static void requireCurrentExecution(
      LifecycleOperation operation, String executorFingerprint, long fencingToken, Instant now) {
    if (operation.getOperationType() != LifecycleOperationType.BACKUP) {
      throw new LifecycleControlException.InvalidRequest("BACKUP_OPERATION_REQUIRED");
    }
    if (!operation.getState().isInFlight()) {
      throw new LifecycleControlException.CompletionConflict();
    }
    requireLeaseOwner(operation, executorFingerprint);
    requireCurrentFencingToken(operation, fencingToken, executorFingerprint);
    if (operation.getLeaseExpiresAt() == null || !now.isBefore(operation.getLeaseExpiresAt())) {
      throw DeniedReport.expired(operation, executorFingerprint);
    }
  }

  private static void requireTerminalReplayBase(
      LifecycleOperation operation,
      String executorFingerprint,
      long fencingToken,
      LifecycleOperationResultCode resultCode) {
    if (!operation.getState().isTerminal()) {
      throw new LifecycleControlException.CompletionConflict();
    }
    requireLeaseOwner(operation, executorFingerprint);
    requireCurrentFencingToken(operation, fencingToken, executorFingerprint);
    if (operation.getResultCode() != resultCode) {
      throw new LifecycleControlException.CompletionConflict();
    }
  }

  private static void requireLeaseOwner(LifecycleOperation operation, String executorFingerprint) {
    if (!executorFingerprint.equals(operation.getLeasedByFingerprint())) {
      throw DeniedReport.wrongExecutor(operation, executorFingerprint);
    }
  }

  private static void requireCurrentFencingToken(
      LifecycleOperation operation, long fencingToken, String executorFingerprint) {
    if (operation.getFencingToken() == null || operation.getFencingToken() != fencingToken) {
      throw DeniedReport.stale(operation, fencingToken, executorFingerprint);
    }
  }

  private static String requirePrincipalFingerprint(String value) {
    if (value == null || value.isBlank() || "unknown".equals(value)) {
      throw new LifecycleControlException.InvalidRequest("CONTROL_PRINCIPAL_REQUIRED");
    }
    return value;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new LifecycleControlException.InvalidRequest(field + "_REQUIRED");
    }
    return value;
  }

  private static String newPublicHandle() {
    byte[] bytes = new byte[HANDLE_RANDOM_BYTES];
    RANDOM.nextBytes(bytes);
    return "ba_" + HexFormat.of().formatHex(bytes);
  }

  private static String canonicalStorageKey(LifecycleOperation operation) {
    return "lifecycle/backup/"
        + operation.getPublicId()
        + "/attempt-"
        + operation.getAttempt()
        + "/token-"
        + operation.getFencingToken()
        + "/artifact.dump.enc";
  }

  public record StageArtifactCommand(
      String operationPublicId,
      String executorFingerprint,
      long fencingToken) {}

  public record FinalizeAvailableCommand(
      String operationPublicId,
      String executorFingerprint,
      long fencingToken,
      String artifactPublicHandle,
      String storageKey,
      AvailableMetadata metadata) {}

  public record FinalizeFailureCommand(
      String operationPublicId,
      String executorFingerprint,
      long fencingToken,
      String artifactPublicHandle,
      LifecycleOperationResultCode resultCode) {}

  public record FinalizeReportCommand(
      String operationPublicId,
      String executorFingerprint,
      long fencingToken,
      String artifactPublicHandle,
      String storageKey,
      LifecycleOperationResultCode resultCode,
      AvailableMetadata metadata) {}

  private static final class DeniedReport extends RuntimeException {
    private final LifecycleOperation operation;
    private final String executor;
    private final Long presentedToken;
    private final DenialType type;

    private DeniedReport(
        LifecycleOperation operation, String executor, Long presentedToken, DenialType type) {
      super(type.name());
      this.operation = operation;
      this.executor = executor;
      this.presentedToken = presentedToken;
      this.type = type;
    }

    private static DeniedReport wrongExecutor(LifecycleOperation operation, String executor) {
      return new DeniedReport(operation, executor, null, DenialType.WRONG_EXECUTOR);
    }

    private static DeniedReport stale(LifecycleOperation operation, long token, String executor) {
      return new DeniedReport(operation, executor, token, DenialType.STALE_TOKEN);
    }

    private static DeniedReport expired(LifecycleOperation operation, String executor) {
      return new DeniedReport(operation, executor, null, DenialType.EXPIRED_LEASE);
    }

    private void audit(LifecycleOperationAuditor auditor) {
      switch (type) {
        case WRONG_EXECUTOR -> auditor.wrongExecutorReportDenied(operation, executor);
        case STALE_TOKEN -> auditor.staleExecutorReportDenied(
            operation, presentedToken == null ? -1L : presentedToken, executor);
        case EXPIRED_LEASE -> auditor.expiredLeaseReportDenied(operation, executor);
      }
    }

    private LifecycleControlException toPublicException() {
      return switch (type) {
        case WRONG_EXECUTOR -> new LifecycleControlException.WrongExecutor();
        case STALE_TOKEN -> new LifecycleControlException.StaleFencingToken();
        case EXPIRED_LEASE -> new LifecycleControlException.LeaseExpired();
      };
    }
  }

  private enum DenialType {
    WRONG_EXECUTOR,
    STALE_TOKEN,
    EXPIRED_LEASE
  }
}
