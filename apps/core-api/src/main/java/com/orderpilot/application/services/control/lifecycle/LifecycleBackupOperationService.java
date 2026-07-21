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
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P1-E2A - the durable backup-operation control service. It owns the full bounded protocol:
 * idempotent request, atomic executor lease with a per-operation monotonic fencing token, and a
 * fencing-checked, idempotent terminal completion.
 *
 * <p>Safety invariants enforced here:
 * <ul>
 *   <li>the executor capability is DISABLED by default; a backup request fails closed BEFORE any
 *       operation row is created when it is disabled;</li>
 *   <li>the operation type is fixed to BACKUP - it is never read from the client;</li>
 *   <li>idempotency is enforced by a database UNIQUE index; a same-key/same-principal repeat returns the
 *       existing operation, a same-key/conflicting-principal repeat fails closed, and a concurrent
 *       duplicate creates exactly one operation (the unique-violation loser re-reads the winner);</li>
 *   <li>a lease is taken under a pessimistic row lock, strictly increasing the fencing token;</li>
 *   <li>only the holder of the current fencing token may complete; a stale token is denied and audited;</li>
 *   <li>terminal completion is idempotent and a conflicting second completion is denied.</li>
 * </ul>
 * No client-supplied path, command, database, container, image, environment, state, executor identity, or
 * fencing token on a staff route ever reaches this service.
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

  /**
   * Requests a backup operation. Fails closed with {@link LifecycleControlException.ExecutorDisabled}
   * BEFORE creating any row when the executor capability is disabled. Idempotent by the required
   * {@code Idempotency-Key}.
   */
  public LifecycleOperation requestBackup(String requestedByFingerprint, String rawIdempotencyKey) {
    if (!executorEnabled) {
      throw new LifecycleControlException.ExecutorDisabled();
    }
    String idempotencyKeyHash = sha256Hex(requireIdempotencyKey(rawIdempotencyKey));

    Optional<LifecycleOperation> existing = readByIdempotencyHash(idempotencyKeyHash);
    if (existing.isPresent()) {
      return resolveIdempotent(existing.get(), requestedByFingerprint);
    }
    try {
      return createQueuedBackup(idempotencyKeyHash, requestedByFingerprint);
    } catch (DataIntegrityViolationException concurrentDuplicate) {
      LifecycleOperation winner = readByIdempotencyHash(idempotencyKeyHash)
          .orElseThrow(() -> concurrentDuplicate);
      return resolveIdempotent(winner, requestedByFingerprint);
    }
  }

  /** Atomically leases the oldest leasable operation to the given executor, or empty if none. */
  @Transactional
  public Optional<LifecycleOperation> leaseNext(String executorFingerprint) {
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
    operation.lease(executorFingerprint, now, leaseDuration);
    repository.save(operation);
    auditor.leaseAcquired(operation, executorFingerprint);
    return Optional.of(operation);
  }

  /**
   * Completes a leased operation with a bounded terminal result code. Only the holder of the current
   * fencing token may complete; a stale token is denied and audited. Completion is idempotent and a
   * conflicting second completion is denied.
   */
  @Transactional
  public LifecycleOperation complete(
      String executorFingerprint,
      String publicId,
      long presentedFencingToken,
      LifecycleOperationResultCode resultCode) {
    Instant now = clock.instant();
    LifecycleOperation operation = repository.findWithLockByPublicId(publicId)
        .orElseThrow(LifecycleControlException.OperationNotFound::new);

    if (operation.getState().isTerminal()) {
      boolean sameToken = operation.getFencingToken() != null
          && operation.getFencingToken() == presentedFencingToken;
      if (sameToken && operation.getResultCode() == resultCode) {
        return operation; // idempotent terminal replay
      }
      throw new LifecycleControlException.CompletionConflict();
    }

    if (operation.getFencingToken() == null
        || operation.getFencingToken() != presentedFencingToken) {
      auditor.staleExecutorReportDenied(operation, presentedFencingToken, executorFingerprint);
      throw new LifecycleControlException.StaleFencingToken();
    }

    operation.complete(resultCode, now);
    repository.save(operation);
    if (resultCode.terminalState() == LifecycleOperationState.SUCCEEDED) {
      auditor.operationSucceeded(operation, executorFingerprint);
    } else {
      auditor.operationFailed(operation, executorFingerprint);
    }
    return operation;
  }

  /** Bounded read of a single operation by opaque public id. */
  @Transactional(readOnly = true)
  public Optional<LifecycleOperation> findByPublicId(String publicId) {
    return repository.findByPublicId(publicId);
  }

  // ---------------------------------------------------------------------------------------------

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
      repository.saveAndFlush(operation); // flush so a unique-key race surfaces here, inside this tx
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
