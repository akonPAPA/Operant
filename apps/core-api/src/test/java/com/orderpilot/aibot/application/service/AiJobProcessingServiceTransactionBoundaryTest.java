package com.orderpilot.aibot.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.orderpilot.aibot.domain.botdefinition.BotDefinitionConfiguration;
import com.orderpilot.aibot.domain.botdefinition.BotDefinitionVersion;
import com.orderpilot.aibot.domain.botdefinition.BotDefinitionVersionState;
import com.orderpilot.aibot.domain.botdefinition.BotHandoffPolicy;
import com.orderpilot.aibot.domain.botdefinition.BotIntentDefinition;
import com.orderpilot.aibot.infrastructure.configuration.OperantAiProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.mockito.Mockito;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * Slice 1 proof: a terminal validation failure of provider output is COMMITTED, not rolled back,
 * while a genuine persistence failure still rolls the authoritative transaction back.
 *
 * <p>Unlike {@link AiJobProcessingServiceTest} (which wires a no-op transaction manager whose
 * {@code doCommit}/{@code doRollback} do nothing and therefore cannot observe rollback), this test
 * wires a {@link RecordingTransactionManager} that counts real commit/rollback callbacks. This
 * proves, at the Spring transaction boundary, that the service's control flow reaches commit on a
 * validation verdict and rollback on a persistence fault.
 *
 * <p>NOT proven here (requires PostgreSQL/Testcontainers, unavailable in this environment): that a
 * real database transaction physically reverts the in-memory aggregate on rollback. The recording
 * manager proves which boundary the code takes; true durable rollback of row state stays
 * NOT_PROVEN without a real transactional datastore.
 */
class AiJobProcessingServiceTransactionBoundaryTest {

  private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String WORKER_A = "worker-A";
  private static final long FENCING_TOKEN = 7L;

  private AiJobRepositoryPort aiJobRepository;
  private BotDefinitionVersionRepositoryPort versionRepository;
  private AiProviderPort aiProviderPort;
  private AiOutputValidator outputValidator;
  private AibotAuditPort auditPort;
  private RecordingTransactionManager txManager;

  @BeforeEach
  void setUp() {
    aiJobRepository =
        Mockito.mock(AiJobRepositoryPort.class, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS));
    versionRepository =
        Mockito.mock(
            BotDefinitionVersionRepositoryPort.class,
            Mockito.withSettings().mockMaker(MockMakers.SUBCLASS));
    aiProviderPort =
        Mockito.mock(AiProviderPort.class, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS));
    outputValidator =
        Mockito.mock(AiOutputValidator.class, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS));
    auditPort =
        Mockito.mock(AibotAuditPort.class, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS));
    txManager = new RecordingTransactionManager();
  }

  // ── Negative test: schema-invalid output commits a terminal INVALID (no rollback) ─────────────

  @Test
  void invalidOutput_commitsTerminalInvalidState_withoutRollbackOrThrow() {
    AiJob durableJob = leasedGenerationJob();
    BotDefinitionVersion version = generatingVersion(durableJob);
    ClaimedAiJob claim = new ClaimedAiJob(durableJob, WORKER_A, FENCING_TOKEN);

    stubEnabledProviderRun(durableJob, version, "{\"schemaVersion\":\"wrong\"}");
    when(outputValidator.parseBotDefinition(any()))
        .thenThrow(new IllegalArgumentException("unknown_schema_version"));

    buildService(enabledProps()).processClaimedJob(claim); // must NOT throw

    assertThat(durableJob.status()).isEqualTo(AiJobStatus.INVALID);
    assertThat(durableJob.failureClass()).isEqualTo("OUTPUT_SCHEMA_INVALID");
    assertThat(durableJob.completedAt()).isEqualTo(NOW);
    assertThat(durableJob.leaseOwner()).isNull();
    assertThat(durableJob.leaseUntil()).isNull();
    assertThat(durableJob.nextAttemptAt()).isNull();

    // The invariant: the terminal state took the COMMIT path, never the rollback path.
    assertThat(txManager.rollbackCount()).as("no rollback on validation verdict").isZero();
    assertThat(txManager.commitCount()).as("markRunning + terminal both commit").isEqualTo(2);

    // Draft restored and a bounded INVALID audit recorded (operator-visible), no success audit.
    assertThat(version.state()).isEqualTo(BotDefinitionVersionState.DRAFT);
    verify(auditPort, atLeastOnce())
        .record(eq(durableJob.tenantId()), any(), eq("AIBOT_AI_JOB_INVALID"), any(), any(), any());
    verify(auditPort, never()).record(any(), any(), eq("AIBOT_AI_JOB_READY"), any(), any(), any());
  }

  // ── Negative test: policy-rejected output maps to OUTPUT_POLICY_REJECTED, still committed ─────

  @Test
  void policyRejectedOutput_commitsTerminalInvalid_withPolicyFailureCode() {
    AiJob durableJob = leasedGenerationJob();
    BotDefinitionVersion version = generatingVersion(durableJob);
    ClaimedAiJob claim = new ClaimedAiJob(durableJob, WORKER_A, FENCING_TOKEN);

    stubEnabledProviderRun(durableJob, version, "{\"actionKey\":\"x\"}");
    when(outputValidator.parseBotDefinition(any()))
        .thenThrow(new IllegalArgumentException("authority_field_forbidden"));

    buildService(enabledProps()).processClaimedJob(claim);

    assertThat(durableJob.status()).isEqualTo(AiJobStatus.INVALID);
    assertThat(durableJob.failureClass()).isEqualTo("OUTPUT_POLICY_REJECTED");
    assertThat(txManager.rollbackCount()).isZero();
  }

  // ── Positive test: valid output still succeeds and commits ────────────────────────────────────

  @Test
  void validOutput_marksSuggestionReadyAndCommits() {
    AiJob durableJob = leasedIntentJob();
    ClaimedAiJob claim = new ClaimedAiJob(durableJob, WORKER_A, FENCING_TOKEN);

    when(aiJobRepository.findByPublicIdAndTenantId(durableJob.publicId(), durableJob.tenantId()))
        .thenReturn(Optional.of(durableJob));
    when(aiJobRepository.save(any())).thenReturn(durableJob);
    when(versionRepository.findByIdAndTenantId(any(), any()))
        .thenReturn(Optional.of(intentCapableVersion(durableJob, "PRICE_INQUIRY")));
    when(aiProviderPort.generateStructured(any())).thenReturn(providerResult("{\"ok\":true}"));
    when(outputValidator.parseIntentClassification(any()))
        .thenReturn(
            new AiOutputValidator.IntentClassification(
                "PRICE_INQUIRY", new BigDecimal("0.90"), Map.of(), "hello", false, java.util.List.of()));

    buildService(enabledProps()).processClaimedJob(claim);

    assertThat(durableJob.status()).isEqualTo(AiJobStatus.SUGGESTION_READY);
    assertThat(durableJob.leaseOwner()).isNull();
    assertThat(txManager.rollbackCount()).as("valid output never rolls back").isZero();
    assertThat(txManager.commitCount()).isEqualTo(2);
    verify(auditPort).record(any(), any(), eq("AIBOT_AI_JOB_READY"), any(), any(), any());
  }

  // ── Slice 4: an intent the bot was NOT configured for cannot expand its capability ────────────

  @Test
  void intentOutsideBotCapability_marksInvalidAndCommits() {
    AiJob durableJob = leasedIntentJob();
    ClaimedAiJob claim = new ClaimedAiJob(durableJob, WORKER_A, FENCING_TOKEN);

    when(aiJobRepository.findByPublicIdAndTenantId(durableJob.publicId(), durableJob.tenantId()))
        .thenReturn(Optional.of(durableJob));
    when(aiJobRepository.save(any())).thenReturn(durableJob);
    // Bot declares only HELP_REQUEST as its capability...
    when(versionRepository.findByIdAndTenantId(any(), any()))
        .thenReturn(Optional.of(intentCapableVersion(durableJob, "HELP_REQUEST")));
    when(aiProviderPort.generateStructured(any())).thenReturn(providerResult("{\"ok\":true}"));
    // ...but the provider classifies the message into an intent the bot never declared.
    when(outputValidator.parseIntentClassification(any()))
        .thenReturn(
            new AiOutputValidator.IntentClassification(
                "ORDER_STATUS_REQUEST", new BigDecimal("0.95"), Map.of(), "hi", false, java.util.List.of()));

    buildService(enabledProps()).processClaimedJob(claim); // must NOT throw

    // Capability expansion is rejected as a committed terminal policy verdict — no raw passthrough.
    assertThat(durableJob.status()).isEqualTo(AiJobStatus.INVALID);
    assertThat(durableJob.failureClass()).isEqualTo("OUTPUT_POLICY_REJECTED");
    assertThat(durableJob.leaseOwner()).isNull();
    assertThat(txManager.rollbackCount()).as("capability rejection commits, never rolls back").isZero();
    assertThat(txManager.commitCount()).isEqualTo(2);
    verify(auditPort, atLeastOnce())
        .record(eq(durableJob.tenantId()), any(), eq("AIBOT_AI_JOB_INVALID"), any(), any(), any());
    verify(auditPort, never()).record(any(), any(), eq("AIBOT_AI_JOB_READY"), any(), any(), any());
  }

  // ── Regression test: a persistence failure DOES roll back and propagate (not silently committed)

  @Test
  void persistenceFailureOnSuccessPath_rollsBackAndPropagates() {
    AiJob durableJob = leasedIntentJob();
    ClaimedAiJob claim = new ClaimedAiJob(durableJob, WORKER_A, FENCING_TOKEN);

    when(aiJobRepository.findByPublicIdAndTenantId(durableJob.publicId(), durableJob.tenantId()))
        .thenReturn(Optional.of(durableJob));
    when(versionRepository.findByIdAndTenantId(any(), any()))
        .thenReturn(Optional.of(intentCapableVersion(durableJob, "PRICE_INQUIRY")));
    when(aiProviderPort.generateStructured(any())).thenReturn(providerResult("{\"ok\":true}"));
    when(outputValidator.parseIntentClassification(any()))
        .thenReturn(
            new AiOutputValidator.IntentClassification(
                "PRICE_INQUIRY", new BigDecimal("0.90"), Map.of(), "hello", false, java.util.List.of()));
    // markRunning save (RUNNING) succeeds; the terminal success save (SUGGESTION_READY) fails.
    when(aiJobRepository.save(any()))
        .thenAnswer(
            invocation -> {
              AiJob saved = invocation.getArgument(0);
              if (saved.status() == AiJobStatus.SUGGESTION_READY) {
                throw new RuntimeException("db_write_failed");
              }
              return saved;
            });

    assertThatThrownBy(() -> buildService(enabledProps()).processClaimedJob(claim))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("db_write_failed");

    // The completion transaction must roll back — a persistence fault is NOT a committed terminal.
    assertThat(txManager.rollbackCount()).as("persistence fault rolls back").isEqualTo(1);
    assertThat(txManager.commitCount()).as("only markRunning committed").isEqualTo(1);
    verify(auditPort, never()).record(any(), any(), eq("AIBOT_AI_JOB_READY"), any(), any(), any());
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────────────────────

  private void stubEnabledProviderRun(AiJob job, BotDefinitionVersion version, String responseText) {
    when(aiJobRepository.findByPublicIdAndTenantId(job.publicId(), job.tenantId()))
        .thenReturn(Optional.of(job));
    when(aiJobRepository.save(any())).thenReturn(job);
    when(versionRepository.findByIdAndTenantId(job.botDefinitionVersionId(), job.tenantId()))
        .thenReturn(Optional.of(version));
    when(aiProviderPort.generateStructured(any())).thenReturn(providerResult(responseText));
  }

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

  private static AiProviderPort.ProviderResult providerResult(String text) {
    return new AiProviderPort.ProviderResult(
        "gemini", "gemini-2.5-flash-lite", text, "req-1", Map.of(), Duration.ofMillis(5), "STOP");
  }

  private static AiJob leasedGenerationJob() {
    return leasedJob(AiJobPurpose.BOT_DEFINITION_GENERATION);
  }

  private static AiJob leasedIntentJob() {
    return leasedJob(AiJobPurpose.BOT_INTENT_CLASSIFICATION);
  }

  private static AiJob leasedJob(AiJobPurpose purpose) {
    Instant created = NOW.minusSeconds(60);
    AiJob job =
        new AiJob(
            "aijob_" + UUID.randomUUID().toString().substring(0, 8),
            UUID.randomUUID(),
            purpose,
            UUID.randomUUID(),
            "idem-" + UUID.randomUUID(),
            "bot-definition-proposal-v1",
            created);
    job.bindPersistence(UUID.randomUUID(), 0L);
    job.claim(WORKER_A, NOW.plusSeconds(300), FENCING_TOKEN, 1, NOW);
    return job;
  }

  private static BotDefinitionVersion generatingVersion(AiJob job) {
    BotDefinitionVersion version =
        new BotDefinitionVersion(
            "bdv_" + UUID.randomUUID().toString().substring(0, 8),
            job.tenantId(),
            UUID.randomUUID(),
            1,
            NOW.minusSeconds(120));
    version.bindPersistence(job.botDefinitionVersionId(), 0L);
    version.markGenerating(NOW.minusSeconds(30)); // DRAFT -> GENERATING (restore/apply legal)
    return version;
  }

  /** A VALIDATED bot version that declares exactly the given intent keys as its capability. */
  private static BotDefinitionVersion intentCapableVersion(AiJob job, String... intentKeys) {
    java.util.List<BotIntentDefinition> intents = new java.util.ArrayList<>();
    for (String key : intentKeys) {
      intents.add(
          new BotIntentDefinition(
              key, key, new BigDecimal("0.50"), "ANSWER", "SAFE_PLAIN_TEXT", true));
    }
    BotDefinitionConfiguration configuration =
        new BotDefinitionConfiguration(
            BotDefinitionConfiguration.SCHEMA_V1, "summary", intents, BotHandoffPolicy.defaults());
    BotDefinitionVersion version =
        new BotDefinitionVersion(
            "bdv_" + UUID.randomUUID().toString().substring(0, 8),
            job.tenantId(),
            UUID.randomUUID(),
            1,
            NOW.minusSeconds(120));
    // Persistence-only restore straight to VALIDATED with the configured capability — no transitions.
    version.rehydrate(
        BotDefinitionVersionState.VALIDATED,
        BotDefinitionConfiguration.SCHEMA_V1,
        configuration,
        "{}",
        "{}",
        NOW.minusSeconds(30),
        job.botDefinitionVersionId(),
        0L);
    return version;
  }

  private static OperantAiProperties enabledProps() {
    OperantAiProperties props = new OperantAiProperties();
    props.setEnabled(true);
    props.getWorker().setEnabled(true);
    return props;
  }

  /** Transaction manager that records real commit/rollback callbacks (unlike a no-op manager). */
  private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
    private final AtomicInteger commits = new AtomicInteger();
    private final AtomicInteger rollbacks = new AtomicInteger();

    int commitCount() {
      return commits.get();
    }

    int rollbackCount() {
      return rollbacks.get();
    }

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      // no-op begin; commit/rollback bookkeeping is what this test observes
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits.incrementAndGet();
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rollbacks.incrementAndGet();
    }
  }
}
