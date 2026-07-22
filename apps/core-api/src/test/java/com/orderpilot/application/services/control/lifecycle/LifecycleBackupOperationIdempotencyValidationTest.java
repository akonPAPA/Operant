package com.orderpilot.application.services.control.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.orderpilot.domain.control.LifecycleOperationRepository;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/** Exact opaque-idempotency validation and denied-no-mutation proof. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class LifecycleBackupOperationIdempotencyValidationTest {
  @Autowired private LifecycleOperationRepository repository;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void surroundingWhitespaceIsRejectedInsteadOfSilentlyChangingSignedIntent() {
    LifecycleOperationAuditor auditor = mock(LifecycleOperationAuditor.class);
    LifecycleBackupOperationService service = new LifecycleBackupOperationService(
        repository, auditor, Clock.systemUTC(), transactionManager, true, 300);

    assertThatThrownBy(() -> service.requestBackup("staff-fingerprint", " attempt-001 "))
        .isInstanceOf(LifecycleControlException.InvalidRequest.class)
        .hasMessage("IDEMPOTENCY_KEY_INVALID");

    assertThat(repository.count()).isZero();
    verifyNoInteractions(auditor);
  }
}
