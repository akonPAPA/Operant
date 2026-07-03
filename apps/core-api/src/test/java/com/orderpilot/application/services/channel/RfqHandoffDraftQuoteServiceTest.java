package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.AiWorkDtos.AiWorkSuggestionResponse;
import com.orderpilot.api.dto.ChannelRfqHandoffDtos.ChannelRfqHandoffResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.ProductCatalogMatchingService;
import com.orderpilot.application.services.ProductSubstitutionService;
import com.orderpilot.application.services.aiwork.AiWorkPublicResponseMapper;
import com.orderpilot.application.services.aiwork.AiWorkService;
import com.orderpilot.application.services.aiwork.DeterministicAiWorkProvider;
import com.orderpilot.application.services.runtime.FeatureEntitlementGuard;
import com.orderpilot.application.services.runtime.QuotaGuard;
import com.orderpilot.application.services.runtime.RateLimitService;
import com.orderpilot.application.services.runtime.RuntimeGuardService;
import com.orderpilot.application.services.runtime.UsageMeterService;
import com.orderpilot.application.services.workspace.RfqToDraftQuoteService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.aiwork.AiWorkSourceType;
import com.orderpilot.domain.aiwork.AiWorkType;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.channel.ChannelProviderType;
import com.orderpilot.domain.channel.InboundChannelEvent;
import com.orderpilot.domain.channel.InboundChannelEventRepository;
import com.orderpilot.domain.integration.ChangeRequestRepository;
import com.orderpilot.domain.integration.CompensationPlanRepository;
import com.orderpilot.domain.integration.ConnectorCommandRepository;
import com.orderpilot.domain.integration.ConnectorSandboxExecutionRepository;
import com.orderpilot.domain.integration.OutboxEventRepository;
import com.orderpilot.domain.tenant.Tenant;
import com.orderpilot.domain.tenant.TenantRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.policy.ActorRole;
import com.orderpilot.security.policy.TenantPolicyService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  RfqHandoffDraftQuoteService.class,
  ChannelRfqHandoffService.class,
  RfqToDraftQuoteService.class,
  ProductCatalogMatchingService.class,
  ProductSubstitutionService.class,
  TenantPolicyService.class,
  AiWorkService.class,
  DeterministicAiWorkProvider.class,
  AiWorkPublicResponseMapper.class,
  RuntimeGuardService.class,
  QuotaGuard.class,
  RateLimitService.class,
  FeatureEntitlementGuard.class,
  UsageMeterService.class,
  AuditEventService.class,
  ObjectMapper.class,
  CoreConfiguration.class
})
class RfqHandoffDraftQuoteServiceTest {
  private static final String DEMO_RFQ =
      "Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.";

  @Autowired private RfqHandoffDraftQuoteService bridgeService;
  @Autowired private ChannelRfqHandoffService handoffService;
  @Autowired private AiWorkService aiWorkService;
  @Autowired private AiWorkPublicResponseMapper aiWorkMapper;
  @Autowired private InboundChannelEventRepository eventRepository;
  @Autowired private TenantRepository tenantRepository;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private ConnectorCommandRepository connectorCommandRepository;
  @Autowired private ConnectorSandboxExecutionRepository sandboxExecutionRepository;
  @Autowired private CompensationPlanRepository compensationPlanRepository;
  @Autowired private ChangeRequestRepository changeRequestRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void reviewedDemoHandoffFlowsThroughAdvisoryAiToVisibleDraftWithoutExternalWrite() {
    UUID tenantId = seedTenant();
    UUID actorId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelRfqHandoffResponse handoff = createHandoff(tenantId, "demo-flow", DEMO_RFQ);
    handoffService.startReview(handoff.id(), actorId);

    AiWorkSuggestionResponse suggestion =
        aiWorkMapper.toResponse(
            aiWorkService.createSuggestion(
                AiWorkType.NEXT_ACTION_SUGGESTION,
                AiWorkSourceType.RFQ_HANDOFF,
                handoff.id(),
                handoff.requestText(),
                "demo-ai:" + handoff.id(),
                actorId));

    var result =
        bridgeService.createDraftQuote(handoff.id(), actorId, ActorRole.OPERATOR);

    assertThat(suggestion.summary()).isNotBlank();
    assertThat(suggestion.advisoryOnly()).isTrue();
    assertThat(result.handoff().status()).isEqualTo("CONVERTED");
    assertThat(result.draftQuote().sourceType()).isEqualTo("RFQ_HANDOFF");
    assertThat(result.draftQuote().requiresHumanReview()).isTrue();
    assertThat(result.draftQuote().status()).isIn("NEEDS_REVIEW", "SUBSTITUTION_REVIEW");
    assertThat(draftQuoteRepository.findByIdAndTenantId(result.draftQuote().id(), tenantId))
        .isPresent();
    assertThat(auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId))
        .extracting("action")
        .contains(
            "AI_WORK_SUGGESTION_CREATED",
            "DRAFT_QUOTE_CREATED",
            "DRAFT_QUOTE_VALIDATION_COMPLETED",
            "CHANNEL_RFQ_HANDOFF_CONVERTED");
    assertNoExternalWriteState();
  }

  @Test
  void routeDerivedIdempotencyReturnsTheSameDraftWithoutDuplicateMutation() {
    UUID tenantId = seedTenant();
    UUID actorId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelRfqHandoffResponse handoff = createHandoff(tenantId, "demo-retry", DEMO_RFQ);
    handoffService.startReview(handoff.id(), actorId);

    var first =
        bridgeService.createDraftQuote(handoff.id(), actorId, ActorRole.OPERATOR);
    var retry =
        bridgeService.createDraftQuote(handoff.id(), actorId, ActorRole.OPERATOR);

    assertThat(retry.draftQuote().id()).isEqualTo(first.draftQuote().id());
    assertThat(draftQuoteRepository.countByTenantId(tenantId)).isEqualTo(1);
    assertNoExternalWriteState();
  }

  @Test
  void tenantCannotCreateDraftFromAnotherTenantsHandoff() {
    UUID tenantA = seedTenant();
    UUID actorA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    ChannelRfqHandoffResponse handoff = createHandoff(tenantA, "tenant-a", DEMO_RFQ);
    handoffService.startReview(handoff.id(), actorA);

    UUID tenantB = seedTenant();
    TenantContext.setTenantId(tenantB);

    assertThatThrownBy(
            () ->
                bridgeService.createDraftQuote(
                    handoff.id(), UUID.randomUUID(), ActorRole.OPERATOR))
        .isInstanceOf(NotFoundException.class);
    assertThat(draftQuoteRepository.countByTenantId(tenantB)).isZero();

    TenantContext.setTenantId(tenantA);
    assertThat(handoffService.get(handoff.id()).status()).isEqualTo("IN_REVIEW");
    assertThat(draftQuoteRepository.countByTenantId(tenantA)).isZero();
    assertNoExternalWriteState();
  }

  @Test
  void pendingOrManuallyConvertedHandoffCannotBypassReviewToCreateDraft() {
    UUID tenantId = seedTenant();
    UUID actorId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelRfqHandoffResponse pending = createHandoff(tenantId, "pending", DEMO_RFQ);

    assertThatThrownBy(
            () ->
                bridgeService.createDraftQuote(
                    pending.id(), actorId, ActorRole.OPERATOR))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be in review");

    handoffService.markConverted(pending.id(), "manual completion", actorId);
    assertThatThrownBy(
            () ->
                bridgeService.createDraftQuote(
                    pending.id(), actorId, ActorRole.OPERATOR))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be in review");
    assertThat(draftQuoteRepository.countByTenantId(tenantId)).isZero();
    assertNoExternalWriteState();
  }

  private ChannelRfqHandoffResponse createHandoff(
      UUID tenantId, String externalEventId, String text) {
    InboundChannelEvent event =
        eventRepository.save(
            new InboundChannelEvent(
                tenantId,
                UUID.randomUUID(),
                ChannelProviderType.TELEGRAM,
                externalEventId,
                "CUSTOMER",
                "demo-sender",
                text,
                "hash-" + externalEventId,
                "{}",
                Instant.parse("2026-07-03T00:00:00Z")));
    return handoffService.createFromChannelEvent(
        new CreateChannelRfqHandoffCommand(
            event.getId(),
            UUID.randomUUID(),
            "TELEGRAM",
            externalEventId,
            event.getSourceActorExternalId(),
            null,
            null,
            text,
            "RFQ_REQUEST"));
  }

  private UUID seedTenant() {
    return tenantRepository
        .save(
            new Tenant(
                "real-flow-" + UUID.randomUUID(),
                "Real Flow Demo",
                "ACTIVE",
                Instant.parse("2026-07-03T00:00:00Z")))
        .getId();
  }

  private void assertNoExternalWriteState() {
    assertThat(connectorCommandRepository.count()).isZero();
    assertThat(sandboxExecutionRepository.count()).isZero();
    assertThat(compensationPlanRepository.count()).isZero();
    assertThat(changeRequestRepository.count()).isZero();
    assertThat(outboxEventRepository.count()).isZero();
  }
}
