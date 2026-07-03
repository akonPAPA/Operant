package com.orderpilot.application.services.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.AiWorkDtos.AiWorkSuggestionResponse;
import com.orderpilot.api.dto.ChannelRfqHandoffDtos.ChannelRfqHandoffResponse;
import com.orderpilot.api.dto.RfqHandoffDraftQuoteDtos.RfqHandoffDecisionRequest;
import com.orderpilot.api.dto.RfqHandoffDraftQuoteDtos.RfqHandoffDecisionResponse;
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
import com.orderpilot.common.idempotency.IdempotencyService;
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
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.tenant.Tenant;
import com.orderpilot.domain.tenant.TenantRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.policy.ActorRole;
import com.orderpilot.security.policy.TenantPolicyService;
import java.math.BigDecimal;
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
  IdempotencyService.class,
  AuditEventService.class,
  ObjectMapper.class,
  CoreConfiguration.class
})
class RfqHandoffDraftQuoteServiceTest {
  private static final String DEMO_RFQ =
      "Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.";

  @Autowired private RfqHandoffDraftQuoteService bridgeService;
  @Autowired private IdempotencyService idempotencyService;
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
  @Autowired private ProductRepository productRepository;
  @Autowired private PriceRuleRepository priceRuleRepository;
  @Autowired private InventorySnapshotRepository inventoryRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void reviewedDemoHandoffFlowsThroughAdvisoryAiToVisibleDraftWithoutExternalWrite() {
    UUID tenantId = seedTenant();
    UUID actorId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    seedDemoProduct();
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
    assertThat(result.draftQuote().lines()).hasSize(1);
    var line = result.draftQuote().lines().get(0);
    assertThat(line.rawSku()).isEqualTo("PAD-OE-04465");
    assertThat(line.normalizedSku()).isEqualTo("PADOE04465");
    assertThat(line.productName()).isEqualTo("Toyota Camry 2018 OEM Front Brake Pad Set");
    assertThat(line.quantity()).isEqualByComparingTo("2");
    assertThat(line.uom()).isEqualTo("EA");
    assertThat(line.unitPrice()).isEqualByComparingTo("65.00");
    assertThat(line.availableStock()).isEqualByComparingTo("100");
    assertThat(line.validationStatus()).isEqualTo("VALIDATED");
    assertThat(line.issueCodes()).doesNotContain("PRODUCT_NOT_RESOLVED");
    assertThat(result.draftQuote().issues())
        .extracting("issueCode")
        .doesNotContain("PRODUCT_NOT_RESOLVED");
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

  @Test
  void validOperatorDecisionCreatesAuditBackedSafeTerminalStateAndRetryIsStable() {
    UUID tenantId = seedTenant();
    UUID actorId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelRfqHandoffResponse handoff = createHandoff(tenantId, "decision-success", DEMO_RFQ);
    handoffService.startReview(handoff.id(), actorId);
    var draft =
        bridgeService.createDraftQuote(
            handoff.id(), actorId, ActorRole.SALES_QUOTE_MANAGER);
    RfqHandoffDecisionRequest request =
        new RfqHandoffDecisionRequest(
            "COMPLETE_DEMO", "Operator completed the safe local demo review.");

    RfqHandoffDecisionResponse first =
        idempotencyService.execute(
            tenantId,
            actorId,
            "decision-success-key",
            "RFQ_HANDOFF_DEMO_DECISION",
            "DRAFT_QUOTE",
            handoff.id().toString(),
            request,
            RfqHandoffDecisionResponse.class,
            () ->
                RfqHandoffDecisionResponse.from(
                    bridgeService.decide(
                        handoff.id(),
                        actorId,
                        ActorRole.SALES_QUOTE_MANAGER,
                        request.decision(),
                        request.note())));
    RfqHandoffDecisionResponse retry =
        idempotencyService.execute(
            tenantId,
            actorId,
            "decision-success-key",
            "RFQ_HANDOFF_DEMO_DECISION",
            "DRAFT_QUOTE",
            handoff.id().toString(),
            request,
            RfqHandoffDecisionResponse.class,
            () ->
                RfqHandoffDecisionResponse.from(
                    bridgeService.decide(
                        handoff.id(),
                        actorId,
                        ActorRole.SALES_QUOTE_MANAGER,
                        request.decision(),
                        request.note())));

    assertThat(first).isEqualTo(retry);
    assertThat(first.decision()).isEqualTo("COMPLETE_DEMO");
    assertThat(first.quoteState()).isEqualTo("DEMO_COMPLETED");
    assertThat(first.terminalState()).isEqualTo("SAFE_DEMO_TERMINAL");
    assertThat(first.auditStatus()).isEqualTo("RECORDED");
    assertThat(first.externalExecution()).isEqualTo("DISABLED");
    assertThat(first.connectorAction()).isEqualTo("NOT_INVOKED");
    assertThat(first.outboxStatus()).isEqualTo("NOT_REQUESTED");
    var persistedQuote =
        draftQuoteRepository
            .findByIdAndTenantId(draft.draftQuote().id(), tenantId)
            .orElseThrow();
    assertThat(persistedQuote.getStatus()).isEqualTo("DEMO_COMPLETED");
    assertThat(persistedQuote.isRequiresHumanReview()).isTrue();
    assertThat(
            auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId).stream()
                .filter(
                    event ->
                        "RFQ_HANDOFF_DEMO_DECISION_RECORDED".equals(event.getAction()))
                .count())
        .isEqualTo(1);
    assertNoExternalWriteState();
  }

  @Test
  void tenantCannotDecideAnotherTenantsRfqDraft() {
    UUID tenantA = seedTenant();
    UUID actorA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    ChannelRfqHandoffResponse handoff = createHandoff(tenantA, "decision-tenant-a", DEMO_RFQ);
    handoffService.startReview(handoff.id(), actorA);
    var draft =
        bridgeService.createDraftQuote(
            handoff.id(), actorA, ActorRole.SALES_QUOTE_MANAGER);

    UUID tenantB = seedTenant();
    TenantContext.setTenantId(tenantB);
    assertThatThrownBy(
            () ->
                bridgeService.decide(
                    handoff.id(),
                    UUID.randomUUID(),
                    ActorRole.SALES_QUOTE_MANAGER,
                    "COMPLETE_DEMO",
                    "Cross-tenant attempt"))
        .isInstanceOf(NotFoundException.class);
    assertThat(draftQuoteRepository.countByTenantId(tenantB)).isZero();

    TenantContext.setTenantId(tenantA);
    assertThat(
            draftQuoteRepository
                .findByIdAndTenantId(draft.draftQuote().id(), tenantA)
                .orElseThrow()
                .getStatus())
        .isNotIn("DEMO_COMPLETED", "DEMO_DECLINED");
    assertNoExternalWriteState();
  }

  @Test
  void terminalDecisionCannotBeChangedByAnInvalidSecondTransition() {
    UUID tenantId = seedTenant();
    UUID actorId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelRfqHandoffResponse handoff =
        createHandoff(tenantId, "decision-invalid-transition", DEMO_RFQ);
    handoffService.startReview(handoff.id(), actorId);
    var draft =
        bridgeService.createDraftQuote(
            handoff.id(), actorId, ActorRole.SALES_QUOTE_MANAGER);
    bridgeService.decide(
        handoff.id(),
        actorId,
        ActorRole.SALES_QUOTE_MANAGER,
        "COMPLETE_DEMO",
        "First terminal decision");

    assertThatThrownBy(
            () ->
                bridgeService.decide(
                    handoff.id(),
                    actorId,
                    ActorRole.SALES_QUOTE_MANAGER,
                    "DECLINE_DEMO",
                    "Conflicting second decision"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not in a valid state");
    assertThat(
            draftQuoteRepository
                .findByIdAndTenantId(draft.draftQuote().id(), tenantId)
                .orElseThrow()
                .getStatus())
        .isEqualTo("DEMO_COMPLETED");
    assertThat(
            auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId).stream()
                .filter(
                    event ->
                        "RFQ_HANDOFF_DEMO_DECISION_RECORDED".equals(event.getAction()))
                .count())
        .isEqualTo(1);
    assertNoExternalWriteState();
  }

  @Test
  void nonQuoteRoleCannotMakeDecisionOrMutateState() {
    UUID tenantId = seedTenant();
    UUID actorId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelRfqHandoffResponse handoff =
        createHandoff(tenantId, "decision-wrong-role", DEMO_RFQ);
    handoffService.startReview(handoff.id(), actorId);
    var draft =
        bridgeService.createDraftQuote(
            handoff.id(), actorId, ActorRole.SALES_QUOTE_MANAGER);

    assertThatThrownBy(
            () ->
                bridgeService.decide(
                    handoff.id(),
                    actorId,
                    ActorRole.READ_ONLY_VIEWER,
                    "COMPLETE_DEMO",
                    "Wrong role"))
        .isInstanceOf(com.orderpilot.security.policy.TenantPolicyException.class);
    assertThat(
            draftQuoteRepository
                .findByIdAndTenantId(draft.draftQuote().id(), tenantId)
                .orElseThrow()
                .getStatus())
        .isNotIn("DEMO_COMPLETED", "DEMO_DECLINED");
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

  private void seedDemoProduct() {
    UUID tenantId = TenantContext.requireTenantId();
    Instant seededAt = Instant.parse("2026-07-03T00:00:00Z");
    Product product =
        productRepository.save(
            new Product(
                tenantId,
                "PAD-OE-04465",
                "Toyota Camry 2018 OEM Front Brake Pad Set",
                null,
                "Brake Pads",
                "Toyota",
                "Toyota",
                "EA",
                "ACTIVE",
                new BigDecimal("42.00"),
                "USD",
                seededAt));
    priceRuleRepository.save(
        new PriceRule(
            tenantId,
            product.getId(),
            null,
            null,
            null,
            BigDecimal.ONE,
            "EA",
            new BigDecimal("65.00"),
            "USD",
            Instant.parse("2026-01-01T00:00:00Z"),
            null,
            10,
            seededAt));
    inventoryRepository.save(
        new InventorySnapshot(
            tenantId,
            product.getId(),
            UUID.randomUUID(),
            new BigDecimal("100"),
            new BigDecimal("100"),
            BigDecimal.ZERO,
            seededAt,
            "TEST",
            null,
            seededAt));
  }

  private void assertNoExternalWriteState() {
    assertThat(connectorCommandRepository.count()).isZero();
    assertThat(sandboxExecutionRepository.count()).isZero();
    assertThat(compensationPlanRepository.count()).isZero();
    assertThat(changeRequestRepository.count()).isZero();
    assertThat(outboxEventRepository.count()).isZero();
  }
}
