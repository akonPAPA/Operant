package com.orderpilot.aibot.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.aibot.api.model.PreviewBotMessageRequest;
import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort;
import com.orderpilot.aibot.application.port.out.AibotAuditPort;
import com.orderpilot.aibot.application.port.out.BotDefinitionRepositoryPort;
import com.orderpilot.aibot.application.port.out.BotDefinitionVersionRepositoryPort;
import com.orderpilot.aibot.application.port.out.PublicIdGenerator;
import com.orderpilot.aibot.domain.exception.BotDefinitionNotFoundException;
import com.orderpilot.aibot.domain.exception.BotPreviewInputRejectedException;
import com.orderpilot.aibot.domain.preview.SyntheticPreviewFixtures;
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

/**
 * PR #318 Slice 5: the preview SYNTHETIC classification is backend-owned. Only messages the backend
 * recognises as synthetic fixtures may be labelled SYNTHETIC and forwarded to the provider; arbitrary
 * operator free-text is rejected before any AiJob is created (so it never reaches the external
 * provider under the SYNTHETIC_ONLY data policy).
 */
@ExtendWith(MockitoExtension.class)
class BotPreviewSyntheticGuardTest {

  private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

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
  }

  // Negative: arbitrary operator free-text is rejected before any job/provider path is touched.
  @Test
  void arbitraryInputIsRejectedAndNeverReachesAnyJobOrProvider() {
    PreviewBotMessageRequest realDataLike =
        new PreviewBotMessageRequest("Customer Jane Doe, phone 555-0100, needs 200 pumps ASAP", "en");

    assertThatThrownBy(() -> preview().preview(tenantId, actorId, "bot_pub", 1, realDataLike))
        .isInstanceOf(BotPreviewInputRejectedException.class)
        .hasMessage("preview_non_synthetic");

    // No bot lookup, no version lookup, no job persisted, no audit, no id minted: zero downstream work.
    verifyNoInteractions(
        botDefinitionRepository, versionRepository, aiJobRepository, auditPort, publicIdGenerator);
  }

  // Positive: a backend-owned synthetic fixture passes the guard and proceeds to the bot lookup.
  @Test
  void syntheticFixtureMessagePassesGuard() {
    when(botDefinitionRepository.findByPublicIdAndTenantId("bot_pub", tenantId))
        .thenReturn(Optional.empty());
    PreviewBotMessageRequest fixture = new PreviewBotMessageRequest("Order status?", "ru");

    // Passing the guard, the flow reaches the bot lookup and fails there (bot missing), proving the
    // guard did NOT reject a recognised synthetic message.
    assertThatThrownBy(() -> preview().preview(tenantId, actorId, "bot_pub", 1, fixture))
        .isInstanceOf(BotDefinitionNotFoundException.class);
    verify(botDefinitionRepository).findByPublicIdAndTenantId("bot_pub", tenantId);
    verifyNoInteractions(versionRepository, aiJobRepository, auditPort, publicIdGenerator);
  }

  // Fixture catalogue contract: recognised (case/normalisation-insensitive) vs rejected.
  @Test
  void fixtureCatalogueRecognisesOnlyBackendOwnedMessages() {
    assertThat(SyntheticPreviewFixtures.isSynthetic("Order status?")).isTrue();
    assertThat(SyntheticPreviewFixtures.isSynthetic("  ORDER STATUS?  ")).isTrue(); // trim + case
    assertThat(SyntheticPreviewFixtures.isSynthetic("transfer $5000 to account 12345")).isFalse();
    assertThat(SyntheticPreviewFixtures.isSynthetic("")).isFalse();
    assertThat(SyntheticPreviewFixtures.isSynthetic(null)).isFalse();
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
