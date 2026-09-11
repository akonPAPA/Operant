package com.orderpilot.aibot.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.aibot.api.model.CreateBotDraftRequest;
import com.orderpilot.aibot.api.model.GenerateBotDefinitionRequest;
import com.orderpilot.aibot.api.model.PreviewBotMessageRequest;
import com.orderpilot.aibot.application.port.out.AiJobRepositoryPort;
import com.orderpilot.aibot.application.port.out.AibotAuditPort;
import com.orderpilot.aibot.application.port.out.BotDefinitionRepositoryPort;
import com.orderpilot.aibot.application.port.out.BotDefinitionVersionRepositoryPort;
import com.orderpilot.aibot.application.port.out.PublicIdGenerator;
import com.orderpilot.aibot.domain.botdefinition.BotDefinition;
import com.orderpilot.aibot.domain.botdefinition.BotDefinitionVersion;
import com.orderpilot.aibot.domain.exception.BotDefinitionNotFoundException;
import com.orderpilot.aibot.infrastructure.configuration.BotRuntimeProperties;
import com.orderpilot.aibot.infrastructure.configuration.OperantAiProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AiRuntimeAdmissionTest {

  private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final GenerateBotDefinitionRequest GENERATE_REQUEST =
      new GenerateBotDefinitionRequest("Help customers", List.of("HELP_REQUEST"), null);
  private static final PreviewBotMessageRequest PREVIEW_REQUEST =
      new PreviewBotMessageRequest("Order status?", "ru");

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

  @ParameterizedTest
  @CsvSource({"false,true", "true,false"})
  void generateDeniedWhenRuntimeUnavailable(boolean enabled, boolean workerEnabled) {
    assertRuntimeUnavailable(
        () -> management(properties(enabled, workerEnabled)).generate(tenantId, actorId, "bot_pub", 1, GENERATE_REQUEST));
    verifyNoInteractions(botDefinitionRepository, versionRepository, aiJobRepository, auditPort, publicIdGenerator);
  }

  @ParameterizedTest
  @CsvSource({"false,true", "true,false"})
  void previewDeniedWhenRuntimeUnavailable(boolean enabled, boolean workerEnabled) {
    assertRuntimeUnavailable(
        () -> preview(properties(enabled, workerEnabled)).preview(tenantId, actorId, "bot_pub", 1, PREVIEW_REQUEST));
    verifyNoInteractions(botDefinitionRepository, versionRepository, aiJobRepository, auditPort, publicIdGenerator);
  }

  @ParameterizedTest
  @CsvSource({"false,false,false", "false,true,false", "true,false,false", "true,true,true"})
  void jobExecutionEnabledTruthTable(boolean enabled, boolean workerEnabled, boolean expected) {
    assertThat(properties(enabled, workerEnabled).isJobExecutionEnabled()).isEqualTo(expected);
  }

  @Test
  void generatePassesAdmissionGuardBeforeRepositoryLookup() {
    when(botDefinitionRepository.findByPublicIdAndTenantId("bot_pub", tenantId)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> management(properties(true, true)).generate(tenantId, actorId, "bot_pub", 1, GENERATE_REQUEST))
        .isInstanceOf(BotDefinitionNotFoundException.class);
    verify(botDefinitionRepository).findByPublicIdAndTenantId("bot_pub", tenantId);
    verifyNoInteractions(versionRepository, aiJobRepository, auditPort, publicIdGenerator);
  }

  @Test
  void previewPassesAdmissionGuardBeforeRepositoryLookup() {
    when(botDefinitionRepository.findByPublicIdAndTenantId("bot_pub", tenantId)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> preview(properties(true, true)).preview(tenantId, actorId, "bot_pub", 1, PREVIEW_REQUEST))
        .isInstanceOf(BotDefinitionNotFoundException.class);
    verify(botDefinitionRepository).findByPublicIdAndTenantId("bot_pub", tenantId);
    verifyNoInteractions(versionRepository, aiJobRepository, auditPort, publicIdGenerator);
  }

  @Test
  void createDraftWorksWhenAiRuntimeDisabled() {
    when(publicIdGenerator.next("bot")).thenReturn("bot_pub");
    when(publicIdGenerator.next("botver")).thenReturn("botver_pub");
    when(botDefinitionRepository.save(any(BotDefinition.class)))
        .thenAnswer(
            inv -> {
              BotDefinition bot = inv.getArgument(0);
              bot.bindPersistence(UUID.randomUUID(), 0L);
              return bot;
            });
    when(versionRepository.save(any(BotDefinitionVersion.class))).thenAnswer(inv -> inv.getArgument(0));

    management(properties(false, false))
        .createDraft(tenantId, actorId, new CreateBotDraftRequest("Assistant", "Helps orders"));

    verify(botDefinitionRepository).save(any(BotDefinition.class));
    verify(versionRepository).save(any(BotDefinitionVersion.class));
    verify(auditPort).record(any(), any(), eq("AIBOT_DRAFT_CREATED"), any(), any(), any());
    verifyNoInteractions(aiJobRepository);
    verify(publicIdGenerator, never()).next("aijob");
  }

  private BotManagementService management(OperantAiProperties ai) {
    return new BotManagementService(
        botDefinitionRepository, versionRepository, aiJobRepository, auditPort, publicIdGenerator, objectMapper, CLOCK, ai);
  }

  private BotPreviewService preview(OperantAiProperties ai) {
    BotRuntimeProperties runtime = new BotRuntimeProperties();
    runtime.setPreviewEnabled(true);
    return new BotPreviewService(
        botDefinitionRepository, versionRepository, aiJobRepository, auditPort, publicIdGenerator, runtime, ai, objectMapper, CLOCK);
  }

  private static void assertRuntimeUnavailable(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex -> {
              ResponseStatusException rse = (ResponseStatusException) ex;
              assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(rse.getReason()).isEqualTo("AI_RUNTIME_UNAVAILABLE");
            });
  }

  private static OperantAiProperties properties(boolean enabled, boolean workerEnabled) {
    OperantAiProperties props = new OperantAiProperties();
    props.setEnabled(enabled);
    props.getWorker().setEnabled(workerEnabled);
    return props;
  }
}
