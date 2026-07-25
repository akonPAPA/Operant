package com.orderpilot.application.services.control.lifecycle;

import com.orderpilot.domain.control.BackupArtifact;
import com.orderpilot.domain.control.BackupArtifactFailureCode;
import com.orderpilot.domain.control.BackupArtifactRepository;
import com.orderpilot.domain.control.BackupArtifactState;
import com.orderpilot.domain.control.LifecycleOperation;
import com.orderpilot.domain.control.LifecycleOperationRepository;
import com.orderpilot.domain.control.LifecycleOperationResultCode;
import com.orderpilot.domain.control.LifecycleOperationState;
import com.orderpilot.domain.control.LifecycleOperationType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable backup-operation control service for request, lease, and artifact-authorized completion. */
@Service
public class LifecycleBackupOperationService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int PUBLIC_ID_RANDOM_BYTES = 12;
  private static final int LEASE_PAGE_SIZE = 1;
  private static final int STAGED_ORPHAN_PAGE_SIZE = 32;

  private final LifecycleOperationRepository repository;
  private final BackupArtifactRepository artifactRepository;
  private final LifecycleOperationAuditor auditor;
  private final Clock clock;
  private final boolean executorEnabled;
  private final Duration leaseDuration;
  private final TransactionTemplate transactionTemplate;

  @Autowired
  public LifecycleBackupOperationService(
      LifecycleOperationRepository repository,
      BackupArtifactRepository artifactRepository,
      LifecycleOperationAuditor auditor,
      Clock clock,
      PlatformTransactionManager transactionManager,
      @Value("${orderpilot.control.lifecycle.executor.enabled:false}") boolean executorEnabled,
      @Value("${orderpilot.control.lifecycle.executor.lease-seconds:300}") long leaseSeconds) {
    this.repository = repository;
    this.artifactRepository = artifactRepository;
    this.auditor = auditor;
    this.clock = clock;
    this.executorEnabled = executorEnabled;
    this.leaseDuration = Duration.ofSeconds(Math.max(1L, leaseSeconds));
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  LifecycleBackupOperationService(
      LifecycleOperationRepository repository,
      LifecycleOperationAuditor auditor,
      Clock clock,
      PlatformTransactionManager transactionManager,
      boolean executorEnabled,
      long leaseSeconds) {
    this(repository, null, auditor, clock, transactionManager, executorEnabled, leaseSeconds);
  }

  public LifecycleOperation requestBackup(String requestedByFingerprint, String rawIdempotencyKey) {
    if (!executorEnabled) {
      throw new LifecycleControlException.ExecutorDisabled();
    }
    String requester = requirePrincipalFingerprint(requestedByFingerprint);
    String idempotencyKeyHash = sha256Hex(requireIdempotencyKey(rawIdempotencyKey));

    Optional<LifecycleOperation> existing = readByIdempotencyHash(idempotencyKeyHash);
    if (existing.isPresent()) {
      return resolveIdempotent(existing.get(), requester);
    }
    try {
      return createQueuedBackup(idempotencyKeyHash, requester);
    } catch (DataIntegrityViolationException concurrentDuplicate) {
      LifecycleOperation winner = readByIdempotencyHash(idempotencyKeyHash)
          .orElseThrow(() -> concurrentDuplicate);
      return resolveIdempotent(winner, requester);
    }
  }

  /** Atomically leases the oldest leasable operation to the authenticated executor. */
  @Transactional
  public Optional<LifecycleOperation> leaseNext(String executorFingerprint) {
    String executor = requirePrincipalFingerprint(executorFingerprint);
    Instant now = clock.instant();
    List<LifecycleOperation> candidates = repository.findLeasableWithLock(
        LifecycleOperationState.QUEUED,
        List.of(LifecycleOperationState.LEASED, LifecycleOperationState.RUNNING),
        now,
        PageRequest.of(0, LEASE_PAGE_SIZE));
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    LifecycleOperation operation = candidates.get(0);
    if (artifactRepository != null && operation.getState().isInFlight()) {
      orphanPriorStagedArtifacts(operation, executor, now);
    }
    operation.lease(executor, now, leaseDuration);
    repository.save(operation);
    auditor.leaseAcquired(operation, executor);
    return Optional.of(operation);
  }

  /**
   * Package-private lower-level transition. REST executor reports must use
   * {@link BackupArtifactPersistenceService}. Success requires a current AVAILABLE artifact for the
   * execution identity; this method is not a general bypass for artifact-free BACKUP_COMPLETED.
   */
  LifecycleOperation complete(
      String executorFingerprint,
      String publicId,
      long presentedFencingToken,
      LifecycleOperationResultCode resultCode) {
    String executor = requirePrincipalFingerprint(executorFingerprint);
    try {
      return transactionTemplate.execute(status -> completeInTransaction(
          executor, publicId, presentedFencingToken, resultCode));
    } catch (DeniedReport denied) {
      denied.audit(auditor);
      throw denied.toPublicException();
    }
  }

  /**
   * Drain remaining STAGED artifacts for this operation in bounded batches. Offset stays at 0 on every
   * iteration because {@link BackupArtifact#markOrphaned} moves each row out of the STAGED predicate
   * ({@code findStagedWithLockByLifecycleOperationId}); the next page-0 query therefore returns the next
   * remaining batch. Advancing {@link Pageable} would skip still-STAGED rows after mutation.
   */
  private void orphanPriorStagedArtifacts(
      LifecycleOperation operation, String executor, Instant now) {
    // Intentionally fixed at offset 0 — drain remaining matches, do not walk OFFSET pages.
    Pageable firstRemainingPage = PageRequest.of(0, STAGED_ORPHAN_PAGE_SIZE);
    while (true) {
      List<BackupArtifact> staged = artifactRepository.findStagedWithLockByLifecycleOperationId(
          operation.getId(), firstRemainingPage);
      if (staged.isEmpty()) {
        return;
      }
      for (BackupArtifact artifact : staged) {
        artifact.markOrphaned(BackupArtifactFailureCode.EXPIRED_LEASE_REPLACED, now);
        artifactRepository.save(artifact);
        auditor.artifactOrphaned(
            operation, artifact, executor, BackupArtifactFailureCode.EXPIRED_LEASE_REPLACED.name());
      }
      if (staged.size() < STAGED_ORPHAN_PAGE_SIZE) {
        return;
      }
    }
  }

  @Transactional(readOnly = true)
  public Optional<LifecycleOperation> findByPublicId(String publicId) {
    return repository.findByPublicId(publicId);
  }

  private LifecycleOperation completeInTransaction(
      String executor,
      String publicId,
      long presentedFencingToken,
      LifecycleOperationResultCode resultCode) {
    LifecycleOperation operation = repository.findWithLockByPublicId(publicId)
        .orElseThrow(LifecycleControlException.OperationNotFound::new);

    if (operation.getState().isTerminal()) {
      requireLeaseOwner(operation, executor);
      requireCurrentFencingToken(operation, presentedFencingToken, executor);
      if (operation.getResultCode() == resultCode) {
        return operation;
      }
      throw new LifecycleControlException.CompletionConflict();
    }

    if (!operation.getState().isInFlight()) {
      throw new LifecycleControlException.CompletionConflict();
    }
    requireLeaseOwner(operation, executor);
    requireCurrentFencingToken(operation, presentedFencingToken, executor);
    Instant now = clock.instant();
    if (operation.getLeaseExpiresAt() == null || !now.isBefore(operation.getLeaseExpiresAt())) {
      throw DeniedReport.expired(operation, executor);
    }
    Objects.requireNonNull(resultCode, "resultCode");
    if (resultCode == LifecycleOperationResultCode.BACKUP_COMPLETED) {
      requireAvailableArtifactForCurrentExecution(operation);
    }

    operation.complete(resultCode, now);
    repository.save(operation);
    if (resultCode.terminalState() == LifecycleOperationState.SUCCEEDED) {
      auditor.operationSucceeded(operation, executor);
    } else {
      auditor.operationFailed(operation, executor);
    }
    return operation;
  }

  private void requireAvailableArtifactForCurrentExecution(LifecycleOperation operation) {
    if (artifactRepository == null) {
      throw new LifecycleControlException.CompletionConflict();
    }
    BackupArtifact artifact = artifactRepository
        .findWithLockByLifecycleOperationIdAndExecutionAttemptAndFencingToken(
            operation.getId(), operation.getAttempt(), operation.getFencingToken())
        .orElseThrow(LifecycleControlException.CompletionConflict::new);
    if (artifact.getState() != BackupArtifactState.AVAILABLE) {
      throw new LifecycleControlException.CompletionConflict();
    }
  }

  private void requireLeaseOwner(LifecycleOperation operation, String executorFingerprint) {
    if (!executorFingerprint.equals(operation.getLeasedByFingerprint())) {
      throw DeniedReport.wrongExecutor(operation, executorFingerprint);
    }
  }

  private void requireCurrentFencingToken(
      LifecycleOperation operation,
      long presentedFencingToken,
      String executorFingerprint) {
    if (operation.getFencingToken() == null || operation.getFencingToken() != presentedFencingToken) {
      throw DeniedReport.stale(operation, presentedFencingToken, executorFingerprint);
    }
  }

  private Optional<LifecycleOperation> readByIdempotencyHash(String idempotencyKeyHash) {
    return transactionTemplate.execute(status ->
        repository.findByOperationTypeAndIdempotencyKeyHash(
            LifecycleOperationType.BACKUP, idempotencyKeyHash));
  }

  private LifecycleOperation createQueuedBackup(
      String idempotencyKeyHash, String requestedByFingerprint) {
    return transactionTemplate.execute(status -> {
      LifecycleOperation operation = LifecycleOperation.queuedBackup(
          newPublicId(), idempotencyKeyHash, requestedByFingerprint, clock.instant());
      repository.saveAndFlush(operation);
      auditor.backupRequested(operation, requestedByFingerprint);
      return operation;
    });
  }

  private LifecycleOperation resolveIdempotent(
      LifecycleOperation existing, String requestedByFingerprint) {
    if (!existing.getRequestedByFingerprint().equals(requestedByFingerprint)) {
      throw new LifecycleControlException.IdempotencyConflict();
    }
    return existing;
  }

  private static String requirePrincipalFingerprint(String value) {
    if (value == null || value.isBlank() || "unknown".equals(value)) {
      throw new LifecycleControlException.InvalidRequest("CONTROL_PRINCIPAL_REQUIRED");
    }
    return value;
  }

  private static String requireIdempotencyKey(String rawIdempotencyKey) {
    if (rawIdempotencyKey == null || rawIdempotencyKey.isBlank()) {
      throw new LifecycleControlException.InvalidRequest("IDEMPOTENCY_KEY_REQUIRED");
    }
    if (!rawIdempotencyKey.equals(rawIdempotencyKey.trim())) {
      throw new LifecycleControlException.InvalidRequest("IDEMPOTENCY_KEY_INVALID");
    }
    if (rawIdempotencyKey.length() > 200) {
      throw new LifecycleControlException.InvalidRequest("IDEMPOTENCY_KEY_TOO_LONG");
    }
    return rawIdempotencyKey;
  }

  private static String newPublicId() {
    byte[] bytes = new byte[PUBLIC_ID_RANDOM_BYTES];
    RANDOM.nextBytes(bytes);
    return "op_" + HexFormat.of().formatHex(bytes);
  }

  private static String sha256Hex(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 unavailable", unavailable);
    }
  }

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
        case STALE_TOKEN -> auditor.staleExecutorReportDenied(operation, presentedToken == null ? -1L : presentedToken, executor);
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