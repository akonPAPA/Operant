package com.orderpilot.aibot.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort;
import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort.ClaimedAiJob;
import com.orderpilot.aibot.application.port.out.AiProviderPort;
import com.orderpilot.aibot.application.port.out.AibotAuditPort;
import com.orderpilot.aibot.application.port.out.BotDefinitionVersionRepositoryPort;
import com.orderpilot.aibot.domain.aijob.AiJob;
import com.orderpilot.aibot.domain.aijob.AiJobPurpose;
import com.orderpilot.aibot.domain.aijob.AiJobStatus;
import com.orderpilot.aibot.infrastructure.configuration.OperantAiProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class AiJobProcessingServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String WORKER_A = "worker-A";
  private static final long FENCING_TOKEN = 7L;

  private AiJobRepositoryPort aiJobRepository;
  private BotDefinitionVersionRepositoryPort versionRepository;
  private AiProviderPort aiProviderPort;
  private AiOutputValidator outputValidator;
  private AibotAuditPort auditPort;

  /** Synchronous no-op transaction manager so TransactionTemplate executes callbacks inline. */
  private final PlatformTransactionManager txManager = new SynchronousNoOpTransactionManager();

  @BeforeEach
  void setUp() {
    // Use SUBCLASS mock maker to be compatible with Java 25 bytecode: Byte Buddy inline
    // instrumentation cannot retransform Java-25-compiled class files in this environment.
    aiJobRepository = Mockito.mock(AiJobRepositoryPort.class, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS));
    versionRepository = Mockito.mock(BotDefinitionVersionRepositoryPort.class, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS));
    aiProviderPort = Mockito.mock(AiProviderPort.class, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS));
    outputValidator = Mockito.mock(AiOutputValidator.class, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS));
    auditPort = Mockito.mock(AibotAuditPort.class, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS));
  }

  // ── Test 1: disabled runtime terminates a valid LEASED claim ───────────────

  @Test
  void disabledRuntime_validLeasedClaim_terminatesWithProviderDisabledAndNeverCallsProvider() {
    AiJob durableJob = leasedJob(WORKER_A, FENCING_TOKEN);
    ClaimedAiJob claim = new ClaimedAiJob(durableJob, WORKER_A, FENCING_TOKEN);

    when(aiJobRepository.findByPublicIdAndTenantId(durableJob.publicId(), durableJob.tenantId()))
        .thenReturn(Optional.of(durableJob));
    when(aiJobRepository.save(durableJob)).thenReturn(durableJob);

    buildService(disabledAiProperties()).processClaimedJob(claim);

    assertThat(durableJob.status()).isEqualTo(AiJobStatus.FAILED);
    assertThat(durableJob.failureClass()).isEqualTo("PROVIDER_DISABLED");
    assertThat(durableJob.completedAt()).isEqualTo(NOW);
    assertThat(durableJob.leaseOwner()).isNull();
    assertThat(durableJob.leaseUntil()).isNull();
    assertThat(durableJob.nextAttemptAt()).isNull();

    verify(aiJobRepository).save(durableJob);
    verifyNoInteractions(aiProviderPort);
    assertThat(durableJob.status()).isNotEqualTo(AiJobStatus.REJECTED);
  }

  // ── Test 2: stale fencing token cannot terminalize ────────────────────────

  @Test
  void disabledRuntime_staleFencingToken_failsClosedWithZeroMutation() {
    // Claim presents fencing token 7, but the durable reload has fencing token 8
    AiJob claimSnapshot = leasedJob(WORKER_A, FENCING_TOKEN);
    ClaimedAiJob claim = new ClaimedAiJob(claimSnapshot, WORKER_A, FENCING_TOKEN);

    AiJob durableWithNewerToken = leasedJob(WORKER_A, 8L);
    when(aiJobRepository.findByPublicIdAndTenantId(claimSnapshot.publicId(), claimSnapshot.tenantId()))
        .thenReturn(Optional.of(durableWithNewerToken));

    buildService(disabledAiProperties()).processClaimedJob(claim);

    // assertLeaseOwnership(worker-A, 7) against durableWithNewerToken(token=8) fails → no save
    verify(aiJobRepository, never()).save(any());
    verifyNoInteractions(aiProviderPort);
    assertThat(durableWithNewerToken.status()).isEqualTo(AiJobStatus.LEASED);
  }

  // ── Test 3: wrong lease owner cannot terminalize ──────────────────────────

  @Test
  void disabledRuntime_wrongLeaseOwner_failsClosedWithZeroMutation() {
    AiJob claimSnapshot = leasedJob(WORKER_A, FENCING_TOKEN);
    ClaimedAiJob claim = new ClaimedAiJob(claimSnapshot, WORKER_A, FENCING_TOKEN);

    // Durable job is owned by worker-B
    AiJob durableOwnedByWorkerB = leasedJob("worker-B", FENCING_TOKEN);
    when(aiJobRepository.findByPublicIdAndTenantId(claimSnapshot.publicId(), claimSnapshot.tenantId()))
        .thenReturn(Optional.of(durableOwnedByWorkerB));

    buildService(disabledAiProperties()).processClaimedJob(claim);

    // assertLeaseOwnership(worker-A, 7) against durableOwnedByWorkerB → fails → no save
    verify(aiJobRepository, never()).save(any());
    verifyNoInteractions(aiProviderPort);
    assertThat(durableOwnedByWorkerB.status()).isEqualTo(AiJobStatus.LEASED);
  }

  // ── Test 4: non-LEASED durable state is not overwritten ───────────────────

  @Test
  void disabledRuntime_durableJobAlreadyRunning_isNotOverwritten() {
    AiJob claimSnapshot = leasedJob(WORKER_A, FENCING_TOKEN);
    ClaimedAiJob claim = new ClaimedAiJob(claimSnapshot, WORKER_A, FENCING_TOKEN);

    // Durable job has advanced to RUNNING
    AiJob durableRunning = runningJob(WORKER_A, FENCING_TOKEN);
    when(aiJobRepository.findByPublicIdAndTenantId(claimSnapshot.publicId(), claimSnapshot.tenantId()))
        .thenReturn(Optional.of(durableRunning));

    buildService(disabledAiProperties()).processClaimedJob(claim);

    // markTerminalFailure sees status != LEASED and returns without saving
    verify(aiJobRepository, never()).save(any());
    verifyNoInteractions(aiProviderPort);
    assertThat(durableRunning.status()).isEqualTo(AiJobStatus.RUNNING);
  }

  // ── Test 5: enabled runtime does not take PROVIDER_DISABLED path ──────────

  @Test
  void enabledRuntime_validLeasedClaim_doesNotTakeProviderDisabledPath() {
    AiJob durableJob = leasedJob(WORKER_A, FENCING_TOKEN);
    ClaimedAiJob claim = new ClaimedAiJob(durableJob, WORKER_A, FENCING_TOKEN);

    AiJob reloaded = leasedJob(WORKER_A, FENCING_TOKEN);
    when(aiJobRepository.findByPublicIdAndTenantId(durableJob.publicId(), durableJob.tenantId()))
        .thenReturn(Optional.of(reloaded));
    when(aiJobRepository.save(any())).thenReturn(reloaded);

    // Provider throws to stop execution at the provider boundary
    when(aiProviderPort.generateStructured(any()))
        .thenThrow(new RuntimeException("provider_test_halt"));

    try {
      buildService(enabledAiProperties()).processClaimedJob(claim);
    } catch (RuntimeException ex) {
      assertThat(ex.getMessage()).isEqualTo("provider_test_halt");
    }

    // The provider was invoked, which proves the enabled runtime path was taken (not PROVIDER_DISABLED).
    // The job state after provider failure is managed by handleProviderFailure — not the concern of Fix 2.
    verify(aiProviderPort).generateStructured(any());
    // Job must not have ended up in FAILED with PROVIDER_DISABLED failure class
    assertThat(reloaded.failureClass()).isNotEqualTo("PROVIDER_DISABLED");
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private AiJobProcessingService buildService(OperantAiProperties props) {
    return new AiJobProcessingService(
        aiJobRepository,
        versionRepository,
        aiProviderPort,
        outputValidator,
        auditPort,
        props,
        new ObjectMapper(),
        CLOCK,
        txManager);
  }

  private static AiJob leasedJob(String owner, long fencingToken) {
    Instant created = NOW.minusSeconds(60);
    AiJob job =
        new AiJob(
            "aijob_" + UUID.randomUUID().toString().substring(0, 8),
            UUID.randomUUID(),
            AiJobPurpose.BOT_DEFINITION_GENERATION,
            UUID.randomUUID(),
            "idem-" + UUID.randomUUID(),
            "bot-definition-proposal-v1",
            created);
    job.bindPersistence(UUID.randomUUID(), 0L);
    job.claim(owner, NOW.plusSeconds(300), fencingToken, 1, NOW);
    return job;
  }

  private static AiJob runningJob(String owner, long fencingToken) {
    AiJob job = leasedJob(owner, fencingToken);
    job.markRunning("gemini", "gemini-2.5-flash-lite", NOW);
    return job;
  }

  private static OperantAiProperties disabledAiProperties() {
    OperantAiProperties props = new OperantAiProperties();
    props.setEnabled(false);
    return props;
  }

  private static OperantAiProperties enabledAiProperties() {
    OperantAiProperties props = new OperantAiProperties();
    props.setEnabled(true);
    props.getWorker().setEnabled(true);
    return props;
  }

  /**
   * Synchronous no-op transaction manager that executes callbacks on the calling thread
   * without real transaction semantics — sufficient for unit tests that do not require
   * rollback or commit behavior.
   */
  private static final class SynchronousNoOpTransactionManager
      extends AbstractPlatformTransactionManager {

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      // no-op
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      // no-op
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      // no-op
    }
  }
}
