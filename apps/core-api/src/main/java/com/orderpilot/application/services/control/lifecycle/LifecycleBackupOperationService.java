package com.orderpilot.application.services.control.lifecycle;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P1-E2A - the durable backup-operation control service. It owns the bounded protocol: idempotent request,
 * atomic executor lease with a per-operation monotonic fencing token, and owner-bound terminal completion.
 *
 * <p>Safety invariants enforced here:
 * <ul>
 *   <li>the executor capability is disabled by default and fails before row creation;</li>
 *   <li>the operation type is fixed to BACKUP and never read from the client;</li>
 *   <li>idempotency is enforced by a database unique index and requesting-principal binding;</li>
 *   <li>a lease is taken under a pessimistic row lock and strictly increases the fencing token;</li>
 *   <li>completion requires the authenticated executor to own the current unexpired lease and present the
 *       current fencing token;</li>
 *   <li>terminal replay is idempotent only for the same owner, token, and result code.</li>
 * </ul>
 */
@Service
public class LifecycleBackupOperationService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int PUBLIC_ID_RANDOM_BYTES = 12;
  private static final int LEASE_PAGE_SIZE = 1;

  private final LifecycleOperationRepository repository;
  private final LifecycleOperationAuditor auditor;
  private final Clock clock;
  private final boolean executorEnabled;
  private final Duration leaseDuration;
  private final TransactionTemplate transactionTemplate;

  public LifecycleBackupOperationService(
      LifecycleOperationRepository repository,
      LifecycleOperationAuditor auditor,
      Clock clock,
      PlatformTransactionManager transactionManager,
      @Value("${orderpilot.control.lifecycle.executor.enabled:false}") boolean executorEnabled,
      @Value("${orderpilot.control.lifecycle.executor.lease-seconds:300}") long leaseSeconds) {
    this.repository = repository;
    this.auditor = auditor;
    this.clock = clock;
    this.executorEnabled = executorEnabled;
    this.leaseDuration = Duration.ofSeconds(Math.max(1L, leaseSeconds));
    this.transactionTemplate = new TransactionTemplate(transactionManager);
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
    operation.lease(executor, now, leaseDuration);
    repository.save(operation);
    auditor.leaseAcquired(operation, executor);
    return Optional.of(operation);
  }

  /**
   * Completes a leased operation under a pessimistic row lock. The current authenticated executor must
   * own the current lease, present the current fencing token, and report before the lease expires.
   */
  @Transactional
  public LifecycleOperation complete(
      String executorFingerprint,
      String publicId,
      long presentedFencingToken,
      LifecycleOperationResultCode resultCode) {
    String executor = requirePrincipalFingerprint(executorFingerprint);
    Instant now = clock.instant();
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
    if (operation.getLeaseExpiresAt() == null || !now.isBefore(operation.getLeaseExpiresAt())) {
      auditor.expiredLeaseReportDenied(operation, executor);
      throw new LifecycleControlException.LeaseExpired();
    }

    operation.complete(Objects.requireNonNull(resultCode, "resultCode"), now);
    repository.save(operation);
    if (resultCode.terminalState() == LifecycleOperationState.SUCCEEDED) {
      auditor.operationSucceeded(operation, executor);
    } else {
      auditor.operationFailed(operation, executor);
    }
    return operation;
  }

  @Transactional(readOnly = true)
  public Optional<LifecycleOperation> findByPublicId(String publicId) {
    return repository.findByPublicId(publicId);
  }

  private void requireLeaseOwner(LifecycleOperation operation, String executorFingerprint) {
    if (!executorFingerprint.equals(operation.getLeasedByFingerprint())) {
      auditor.wrongExecutorReportDenied(operation, executorFingerprint);
      throw new LifecycleControlException.WrongExecutor();
    }
  }

  private void requireCurrentFencingToken(
      LifecycleOperation operation,
      long presentedFencingToken,
      String executorFingerprint) {
    if (operation.getFencingToken() == null
        || operation.getFencingToken() != presentedFencingToken) {
      auditor.staleExecutorReportDenied(operation, presentedFencingToken, executorFingerprint);
      throw new LifecycleControlException.StaleFencingToken();
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
    String trimmed = rawIdempotencyKey.trim();
    if (trimmed.length() > 200) {
      throw new LifecycleControlException.InvalidRequest("IDEMPOTENCY_KEY_TOO_LONG");
    }
    return trimmed;
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
}
