package com.orderpilot.aibot.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.aibot.api.model.PreviewBotMessageRequest;
import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort;
import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort.IdempotentSave;
import com.orderpilot.aibot.application.port.out.AibotAuditPort;
import com.orderpilot.aibot.application.port.out.BotDefinitionRepositoryPort;
import com.orderpilot.aibot.application.port.out.BotDefinitionVersionRepositoryPort;
import com.orderpilot.aibot.application.port.out.PublicIdGenerator;
import com.orderpilot.aibot.domain.aijob.AiJob;
import com.orderpilot.aibot.domain.aijob.AiJobPurpose;
import com.orderpilot.aibot.domain.botdefinition.BotDefinition;
import com.orderpilot.aibot.domain.botdefinition.BotDefinitionVersion;
import com.orderpilot.aibot.infrastructure.configuration.BotRuntimeProperties;
import com.orderpilot.aibot.infrastructure.configuration.OperantAiProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * PR #318 Slice 6 (service branch): the preview flow reacts correctly to the concurrency-safe
 * idempotent insert result — audit exactly once on genuine creation, silent idempotent hit on a lost
 * race, and a 409 when the same key carries a different request fingerprint. The DB-level race safety
 * itself is proven in {@code AiJobIdempotencyConcurrencyPostgresIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class BotPreviewIdempotencyTest {

  private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String BOT_PUB = "bot_pub";
  private static final PreviewBotMessageRequest FIXTURE = new PreviewBotMessageRequest("Order status?", "ru");

  @Mock private BotDefinitionRepositoryPort botDefinitionRepository;
  @Mock private BotDefinitionVersionRepositoryPort versionRepository;
  @Mock private AiJobRepositoryPort aiJobRepository;
  @Mock private AibotAuditPort auditPort;
  @Mock private PublicIdGenerator publicIdGenerator;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private UUID tenantId;
  private UUID actorId;

  @BeforeEach
  void setUp() {
    tenantId = UUID.randomUUID();
    actorId = UUID.randomUUID();
    BotDefinition bot = new BotDefinition(BOT_PUB, tenantId, "Bot", "desc", null, NOW.minusSeconds(60));
    bot.bindPersistence(UUID.randomUUID(), 0L);
    BotDefinitionVersion version =
        new BotDefinitionVersion("bdv_1", tenantId, bot.id(), 1, NOW.minusSeconds(60));
    version.bindPersistence(UUID.randomUUID(), 0L); // DRAFT -> previewable
    when(botDefinitionRepository.findByPublicIdAndTenantId(BOT_PUB, tenantId))
        .thenReturn(Optional.of(bot));
    when(versionRepository.findByBotDefinitionIdAndVersionNumberAndTenantId(any(), eq(1), eq(tenantId)))
        .thenReturn(Optional.of(version));
    when(publicIdGenerator.next("aijob")).thenReturn("aijob_new");
    when(aiJobRepository.findByTenantIdAndPurposeAndIdempotencyKey(
            eq(tenantId), eq(AiJobPurpose.BOT_INTENT_CLASSIFICATION), any()))
        .thenReturn(Optional.empty()); // no pre-existing row -> reach the idempotent insert
  }

  @Test
  void genuineInsert_recordsAuditOnce() {
    when(aiJobRepository.saveNewIdempotent(any()))
        .thenAnswer(inv -> new IdempotentSave(inv.getArgument(0), true));

    preview().preview(tenantId, actorId, BOT_PUB, 1, FIXTURE);

    verify(auditPort).record(eq(tenantId), eq(actorId), eq("AIBOT_PREVIEW_REQUESTED"), any(), any(), any());
  }

  @Test
  void lostRace_returnsWinnerWithoutAudit() {
    // inserted=false: a concurrent writer won; same request fingerprint -> idempotent hit, no audit.
    when(aiJobRepository.saveNewIdempotent(any()))
        .thenAnswer(inv -> new IdempotentSave(inv.getArgument(0), false));

    var response = preview().preview(tenantId, actorId, BOT_PUB, 1, FIXTURE);

    assertThat(response).isNotNull();
    verify(auditPort, never())
        .record(any(), any(), eq("AIBOT_PREVIEW_REQUESTED"), any(), any(), any());
  }

  @Test
  void sameKeyDifferentFingerprint_conflicts() {
    // The winning row carries a DIFFERENT fingerprint: same idempotency key, different request.
    AiJob conflicting =
        new AiJob("aijob_other", tenantId, AiJobPurpose.BOT_INTENT_CLASSIFICATION,
            UUID.randomUUID(), "idem-x", "op-cap-m18.v1", NOW);
    conflicting.attachRequestEnvelope("{}", "d".repeat(64), "SYNTHETIC");
    when(aiJobRepository.saveNewIdempotent(any())).thenReturn(new IdempotentSave(conflicting, false));

    assertThatThrownBy(() -> preview().preview(tenantId, actorId, BOT_PUB, 1, FIXTURE))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("IDEMPOTENCY_CONFLICT");
    verify(auditPort, never())
        .record(any(), any(), eq("AIBOT_PREVIEW_REQUESTED"), any(), any(), any());
  }

  private BotPreviewService preview() {
    BotRuntimeProperties runtime = new BotRuntimeProperties();
    runtime.setPreviewEnabled(true);
    OperantAiProperties ai = new OperantAiProperties();
    ai.setEnabled(true);
    ai.getWorker().setEnabled(true);
    return new BotPreviewService(
        botDefinitionRepository,
        versionRepository,
        aiJobRepository,
        auditPort,
        publicIdGenerator,
        runtime,
        ai,
        objectMapper,
        CLOCK);
  }
}
