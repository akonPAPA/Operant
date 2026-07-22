package com.orderpilot.application.services.control.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.orderpilot.domain.control.LifecycleOperation;
import com.orderpilot.domain.control.LifecycleOperationRepository;
import com.orderpilot.domain.control.LifecycleOperationResultCode;
import com.orderpilot.domain.control.LifecycleOperationState;
import com.orderpilot.domain.control.LifecycleOperationType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * P1-E2A - H2 protocol proofs for the durable backup-operation control service: the executor-disabled
 * gate, idempotency (including the DB unique constraint), the lease transition + monotonic fencing token,
 * owner- and fencing-checked completion, terminal idempotency, and conflicting-completion denial, plus
 * that the bounded audit events fire on each sensitive transition.
 *
 * <p>Genuinely CONCURRENT lease/idempotency behaviour needs real PostgreSQL row locking and is proven
 * separately by {@code LifecycleBackupOperationControlPostgresIntegrationTest} (Docker-gated).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class LifecycleBackupOperationServiceTest {
  private static final String STAFF_FP = "staff-fingerprint-1";
  private static final String OTHER_STAFF_FP = "staff-fingerprint-2";
  private static final String EXEC_FP = "executor-fingerprint-1";
  private static final String SECOND_EXEC_FP = "executor-fingerprint-2";
  private static final Instant T0 = Instant.parse("2026-07-20T10:00:00Z");
  private static final long LEASE_SECONDS = 300L;

  @Autowired private LifecycleOperationRepository repository;
  @Autowired private PlatformTransactionManager transactionManager;

  private MutableClock clock;
  private LifecycleOperationAuditor auditor;
  private LifecycleBackupOperationService service;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(T0);
    auditor = mock(LifecycleOperationAuditor.class);
    service = enabledService();
  }

  private LifecycleBackupOperationService enabledService() {
    return new LifecycleBackupOperationService(
        repository, auditor, clock, transactionManager, true, LEASE_SECONDS);
  }

  // --- executor-disabled gate --------------------------------------------------------------------

  @Test
  void backupRequestFailsClosedBeforeCreatingOperationWhenExecutorDisabled() {
    LifecycleBackupOperationService disabled = new LifecycleBackupOperationService(
        repository, auditor, clock, transactionManager, false, LEASE_SECONDS);

    assertThatThrownBy(() -> disabled.requestBackup(STAFF_FP, "idem-1"))
        .isInstanceOf(LifecycleControlException.ExecutorDisabled.class);
    assertThat(repository.count()).isZero();
    verifyNoInteractions(auditor);
  }

  @Test
  void missingIdempotencyKeyIsRejectedAndCreatesNothing() {
    assertThatThrownBy(() -> service.requestBackup(STAFF_FP, "  "))
        .isInstanceOf(LifecycleControlException.InvalidRequest.class);
    assertThat(repository.count()).isZero();
  }

  // --- idempotency -------------------------------------------------------------------------------

  @Test
  void backupRequestCreatesOneQueuedOperationAndAudits() {
    LifecycleOperation op = service.requestBackup(STAFF_FP, "idem-1");

    assertThat(op.getOperationType()).isEqualTo(LifecycleOperationType.BACKUP);
    assertThat(op.getState()).isEqualTo(LifecycleOperationState.QUEUED);
    assertThat(op.getAttempt()).isZero();
    assertThat(op.getFencingToken()).isNull();
    assertThat(op.getPublicId()).startsWith("op_");
    assertThat(repository.count()).isEqualTo(1);
    verify(auditor).backupRequested(op, STAFF_FP);
  }

  @Test
  void sameIdempotencyKeyAndPrincipalReturnsExistingOperation() {
    LifecycleOperation first = service.requestBackup(STAFF_FP, "idem-1");
    LifecycleOperation second = service.requestBackup(STAFF_FP, "idem-1");

    assertThat(second.getPublicId()).isEqualTo(first.getPublicId());
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  void sameIdempotencyKeyWithConflictingPrincipalIsRejected() {
    service.requestBackup(STAFF_FP, "idem-1");

    assertThatThrownBy(() -> service.requestBackup(OTHER_STAFF_FP, "idem-1"))
        .isInstanceOf(LifecycleControlException.IdempotencyConflict.class);
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  void databaseUniqueConstraintRejectsDuplicateIdempotencyKey() {
    LifecycleOperation a = LifecycleOperation.queuedBackup("op_a", "dup-hash", STAFF_FP, T0);
    LifecycleOperation b = LifecycleOperation.queuedBackup("op_b", "dup-hash", STAFF_FP, T0);
    repository.saveAndFlush(a);

    assertThatThrownBy(() -> repository.saveAndFlush(b))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // --- lease + fencing ---------------------------------------------------------------------------

  @Test
  void leaseTransitionsQueuedToLeasedWithFencingTokenOne() {
    LifecycleOperation queued = service.requestBackup(STAFF_FP, "idem-1");

    Optional<LifecycleOperation> leased = service.leaseNext(EXEC_FP);

    assertThat(leased).isPresent();
    LifecycleOperation op = leased.get();
    assertThat(op.getPublicId()).isEqualTo(queued.getPublicId());
    assertThat(op.getState()).isEqualTo(LifecycleOperationState.LEASED);
    assertThat(op.getFencingToken()).isEqualTo(1L);
    assertThat(op.getAttempt()).isEqualTo(1);
    assertThat(op.getLeasedByFingerprint()).isEqualTo(EXEC_FP);
    assertThat(op.getLeaseExpiresAt()).isEqualTo(T0.plusSeconds(LEASE_SECONDS));
    verify(auditor).leaseAcquired(op, EXEC_FP);
  }

  @Test
  void leaseReturnsEmptyWhenNothingIsLeasable() {
    assertThat(service.leaseNext(EXEC_FP)).isEmpty();
  }

  @Test
  void nonExpiredLeasedOperationIsNotReLeased() {
    service.requestBackup(STAFF_FP, "idem-1");
    service.leaseNext(EXEC_FP);

    // No time has passed; the lease is still valid.
    assertThat(service.leaseNext(SECOND_EXEC_FP)).isEmpty();
  }

  @Test
  void expiredLeaseIsReLeasedAndFencingTokenIncrements() {
    service.requestBackup(STAFF_FP, "idem-1");
    LifecycleOperation first = service.leaseNext(EXEC_FP).orElseThrow();
    assertThat(first.getFencingToken()).isEqualTo(1L);

    clock.advance(Duration.ofSeconds(LEASE_SECONDS + 1));

    LifecycleOperation second = service.leaseNext(SECOND_EXEC_FP).orElseThrow();
    assertThat(second.getPublicId()).isEqualTo(first.getPublicId());
    assertThat(second.getFencingToken()).isEqualTo(2L); // strictly increased
    assertThat(second.getAttempt()).isEqualTo(2);
  }

  // --- completion --------------------------------------------------------------------------------

  @Test
  void completeWithCurrentFencingTokenMarksSucceededAndAudits() {
    service.requestBackup(STAFF_FP, "idem-1");
    LifecycleOperation leased = service.leaseNext(EXEC_FP).orElseThrow();

    LifecycleOperation done = service.complete(
        EXEC_FP, leased.getPublicId(), leased.getFencingToken(),
        LifecycleOperationResultCode.BACKUP_COMPLETED);

    assertThat(done.getState()).isEqualTo(LifecycleOperationState.SUCCEEDED);
    assertThat(done.getResultCode()).isEqualTo(LifecycleOperationResultCode.BACKUP_COMPLETED);
    verify(auditor).operationSucceeded(done, EXEC_FP);
  }

  @Test
  void completeWithFailureCodeMarksFailed() {
    service.requestBackup(STAFF_FP, "idem-1");
    LifecycleOperation leased = service.leaseNext(EXEC_FP).orElseThrow();

    LifecycleOperation done = service.complete(
        EXEC_FP, leased.getPublicId(), leased.getFencingToken(),
        LifecycleOperationResultCode.BACKUP_FAILED_EXECUTION);

    assertThat(done.getState()).isEqualTo(LifecycleOperationState.FAILED);
    assertThat(done.getResultCode()).isEqualTo(LifecycleOperationResultCode.BACKUP_FAILED_EXECUTION);
    verify(auditor).operationFailed(done, EXEC_FP);
  }

  @Test
  void staleFencingTokenCompletionByCurrentOwnerIsDeniedAndOperationUnchanged() {
    service.requestBackup(STAFF_FP, "idem-1");
    LifecycleOperation first = service.leaseNext(EXEC_FP).orElseThrow();
    long staleToken = first.getFencingToken(); // 1

    clock.advance(Duration.ofSeconds(LEASE_SECONDS + 1));
    LifecycleOperation reLeased = service.leaseNext(SECOND_EXEC_FP).orElseThrow(); // token 2

    assertThatThrownBy(() -> service.complete(
        SECOND_EXEC_FP, reLeased.getPublicId(), staleToken,
        LifecycleOperationResultCode.BACKUP_COMPLETED))
        .isInstanceOf(LifecycleControlException.StaleFencingToken.class);

    LifecycleOperation reloaded = repository.findByPublicId(reLeased.getPublicId()).orElseThrow();
    assertThat(reloaded.getState()).isEqualTo(LifecycleOperationState.LEASED);
    assertThat(reloaded.getResultCode()).isNull();
    verify(auditor).staleExecutorReportDenied(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(staleToken),
        org.mockito.ArgumentMatchers.eq(SECOND_EXEC_FP));
  }

  @Test
  void terminalCompletionIsIdempotent() {
    service.requestBackup(STAFF_FP, "idem-1");
    LifecycleOperation leased = service.leaseNext(EXEC_FP).orElseThrow();
    long token = leased.getFencingToken();

    LifecycleOperation first = service.complete(
        EXEC_FP, leased.getPublicId(), token, LifecycleOperationResultCode.BACKUP_COMPLETED);
    LifecycleOperation replay = service.complete(
        EXEC_FP, leased.getPublicId(), token, LifecycleOperationResultCode.BACKUP_COMPLETED);

    assertThat(first.getState()).isEqualTo(LifecycleOperationState.SUCCEEDED);
    assertThat(replay.getState()).isEqualTo(LifecycleOperationState.SUCCEEDED);
    assertThat(replay.getPublicId()).isEqualTo(first.getPublicId());
  }

  @Test
  void conflictingTerminalCompletionIsDenied() {
    service.requestBackup(STAFF_FP, "idem-1");
    LifecycleOperation leased = service.leaseNext(EXEC_FP).orElseThrow();
    long token = leased.getFencingToken();
    service.complete(EXEC_FP, leased.getPublicId(), token,
        LifecycleOperationResultCode.BACKUP_COMPLETED);

    assertThatThrownBy(() -> service.complete(
        EXEC_FP, leased.getPublicId(), token,
        LifecycleOperationResultCode.BACKUP_FAILED_EXECUTION))
        .isInstanceOf(LifecycleControlException.CompletionConflict.class);

    LifecycleOperation reloaded = repository.findByPublicId(leased.getPublicId()).orElseThrow();
    assertThat(reloaded.getState()).isEqualTo(LifecycleOperationState.SUCCEEDED);
    assertThat(reloaded.getResultCode()).isEqualTo(LifecycleOperationResultCode.BACKUP_COMPLETED);
  }

  @Test
  void completingAnUnknownOperationIsNotFound() {
    assertThatThrownBy(() -> service.complete(
        EXEC_FP, "op_does_not_exist", 1L, LifecycleOperationResultCode.BACKUP_COMPLETED))
        .isInstanceOf(LifecycleControlException.OperationNotFound.class);
    verify(auditor, never()).operationSucceeded(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.anyString());
  }

  // -----------------------------------------------------------------------------------------------

  /** Minimal advanceable clock so lease-expiry re-lease and fencing increment are deterministic. */
  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant start) {
      this.now = start;
    }

    private void advance(Duration duration) {
      this.now = this.now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
