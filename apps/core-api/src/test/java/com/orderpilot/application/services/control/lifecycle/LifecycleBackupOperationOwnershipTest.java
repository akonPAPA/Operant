package com.orderpilot.application.services.control.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.orderpilot.domain.control.LifecycleOperation;
import com.orderpilot.domain.control.LifecycleOperationRepository;
import com.orderpilot.domain.control.LifecycleOperationResultCode;
import com.orderpilot.domain.control.LifecycleOperationState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/** Owner, fencing, and expiry proofs for terminal lifecycle reports. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class LifecycleBackupOperationOwnershipTest {
  private static final String STAFF = "staff-fingerprint";
  private static final String EXECUTOR_A = "executor-a-fingerprint";
  private static final String EXECUTOR_B = "executor-b-fingerprint";
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
    service = new LifecycleBackupOperationService(
        repository, auditor, clock, transactionManager, true, LEASE_SECONDS);
  }

  @Test
  void differentExecutorWithCopiedCurrentTokenIsDeniedWithoutMutation() {
    service.requestBackup(STAFF, "idem-owner-1");
    LifecycleOperation leased = service.leaseNext(EXECUTOR_A).orElseThrow();

    assertThatThrownBy(() -> service.complete(
        EXECUTOR_B,
        leased.getPublicId(),
        leased.getFencingToken(),
        LifecycleOperationResultCode.BACKUP_COMPLETED))
        .isInstanceOf(LifecycleControlException.WrongExecutor.class);

    LifecycleOperation reloaded = repository.findByPublicId(leased.getPublicId()).orElseThrow();
    assertThat(reloaded.getState()).isEqualTo(LifecycleOperationState.LEASED);
    assertThat(reloaded.getResultCode()).isNull();
    assertThat(reloaded.getLeasedByFingerprint()).isEqualTo(EXECUTOR_A);
    verify(auditor).wrongExecutorReportDenied(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(EXECUTOR_B));
    verify(auditor, never()).operationSucceeded(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    verify(auditor, never()).operationFailed(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void currentOwnerCannotCompleteAfterLeaseExpiryAndStateDoesNotChange() {
    service.requestBackup(STAFF, "idem-expiry-1");
    LifecycleOperation leased = service.leaseNext(EXECUTOR_A).orElseThrow();
    clock.advance(Duration.ofSeconds(LEASE_SECONDS));

    assertThatThrownBy(() -> service.complete(
        EXECUTOR_A,
        leased.getPublicId(),
        leased.getFencingToken(),
        LifecycleOperationResultCode.BACKUP_COMPLETED))
        .isInstanceOf(LifecycleControlException.LeaseExpired.class);

    LifecycleOperation reloaded = repository.findByPublicId(leased.getPublicId()).orElseThrow();
    assertThat(reloaded.getState()).isEqualTo(LifecycleOperationState.LEASED);
    assertThat(reloaded.getResultCode()).isNull();
    verify(auditor).expiredLeaseReportDenied(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(EXECUTOR_A));
  }

  @Test
  void terminalReplayRequiresOriginalLeaseOwnerAsWellAsTokenAndResult() {
    service.requestBackup(STAFF, "idem-terminal-owner-1");
    LifecycleOperation leased = service.leaseNext(EXECUTOR_A).orElseThrow();
    long token = leased.getFencingToken();
    service.complete(
        EXECUTOR_A,
        leased.getPublicId(),
        token,
        LifecycleOperationResultCode.BACKUP_COMPLETED);

    assertThatThrownBy(() -> service.complete(
        EXECUTOR_B,
        leased.getPublicId(),
        token,
        LifecycleOperationResultCode.BACKUP_COMPLETED))
        .isInstanceOf(LifecycleControlException.WrongExecutor.class);

    LifecycleOperation reloaded = repository.findByPublicId(leased.getPublicId()).orElseThrow();
    assertThat(reloaded.getState()).isEqualTo(LifecycleOperationState.SUCCEEDED);
    assertThat(reloaded.getResultCode()).isEqualTo(LifecycleOperationResultCode.BACKUP_COMPLETED);
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant start) {
      this.now = start;
    }

    private void advance(Duration duration) {
      now = now.plus(duration);
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
