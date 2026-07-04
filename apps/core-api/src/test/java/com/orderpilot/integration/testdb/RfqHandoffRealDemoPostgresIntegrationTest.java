package com.orderpilot.integration.testdb;

import static com.orderpilot.support.DatabaseIntegrationTestBase.CLEAN;
import static com.orderpilot.support.DatabaseIntegrationTestBase.CUSTOMERS;
import static com.orderpilot.support.DatabaseIntegrationTestBase.INVENTORY;
import static com.orderpilot.support.DatabaseIntegrationTestBase.PRICING;
import static com.orderpilot.support.DatabaseIntegrationTestBase.PRODUCTS;
import static com.orderpilot.support.DatabaseIntegrationTestBase.TENANTS;
import static com.orderpilot.support.DatabaseIntegrationTestBase.USERS_ROLES;
import static com.orderpilot.support.TestTenantFixtures.TENANT_A;
import static com.orderpilot.support.TestTenantFixtures.TENANT_B;
import static com.orderpilot.support.TestUserFixtures.USER_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.AiWorkDtos.AiWorkSuggestionResponse;
import com.orderpilot.api.dto.ChannelRfqHandoffDtos.ChannelRfqHandoffResponse;
import com.orderpilot.api.dto.RfqHandoffDraftQuoteDtos.RfqHandoffDecisionRequest;
import com.orderpilot.api.dto.RfqHandoffDraftQuoteDtos.RfqHandoffDecisionResponse;
import com.orderpilot.application.services.aiwork.AiWorkPublicResponseMapper;
import com.orderpilot.application.services.aiwork.AiWorkService;
import com.orderpilot.application.services.channel.ChannelRfqHandoffService;
import com.orderpilot.application.services.channel.CreateChannelRfqHandoffCommand;
import com.orderpilot.application.services.channel.LocalDemoRfqIntakeService;
import com.orderpilot.application.services.channel.RfqHandoffDraftQuoteService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.idempotency.IdempotencyService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.aiwork.AiWorkSourceType;
import com.orderpilot.domain.aiwork.AiWorkType;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.bot.BotRfqRequestRepository;
import com.orderpilot.domain.channel.ChannelConnection;
import com.orderpilot.domain.channel.ChannelConnectionRepository;
import com.orderpilot.domain.channel.ChannelProviderType;
import com.orderpilot.domain.channel.ChannelRfqHandoffRepository;
import com.orderpilot.domain.channel.InboundChannelEvent;
import com.orderpilot.domain.channel.InboundChannelEventRepository;
import com.orderpilot.domain.integration.ChangeRequestRepository;
import com.orderpilot.domain.integration.CompensationPlanRepository;
import com.orderpilot.domain.integration.ConnectorCommandRepository;
import com.orderpilot.domain.integration.ConnectorSandboxExecutionRepository;
import com.orderpilot.domain.integration.OutboxEventRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.security.policy.ActorRole;
import com.orderpilot.security.policy.TenantPolicyException;
import com.orderpilot.support.DatabaseIntegrationTestBase;
import com.orderpilot.support.RequiresPostgresIntegration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

@Sql(scripts = {CLEAN, TENANTS, USERS_ROLES, CUSTOMERS, PRODUCTS, INVENTORY, PRICING})
@RequiresPostgresIntegration
class RfqHandoffRealDemoPostgresIntegrationTest extends DatabaseIntegrationTestBase {
  private static final String DEMO_RFQ = "Need 2 EA BRK-001 brake pads for a safe demo.";

  @Autowired private RfqHandoffDraftQuoteService bridgeService;
  @Autowired private LocalDemoRfqIntakeService localDemoRfqIntakeService;
  @Autowired private ChannelRfqHandoffService handoffService;
  @Autowired private AiWorkService aiWorkService;
  @Autowired private AiWorkPublicResponseMapper aiWorkMapper;
  @Autowired private IdempotencyService idempotencyService;
  @Autowired private ChannelConnectionRepository connectionRepository;
  @Autowired private ChannelRfqHandoffRepository channelRfqHandoffRepository;
  @Autowired private InboundChannelEventRepository eventRepository;
  @Autowired private BotRfqRequestRepository botRfqRequestRepository;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private ConnectorCommandRepository connectorCommandRepository;
  @Autowired private ConnectorSandboxExecutionRepository sandboxExecutionRepository;
  @Autowired private CompensationPlanRepository compensationPlanRepository;
  @Autowired private ChangeRequestRepository changeRequestRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;

  @Test
  void realPostgresFlowPersistsSafeTerminalStateAndStableRetry() {
    UUID actorId = USER_A;
    TenantContext.setTenantId(TENANT_A);
    ChannelRfqHandoffResponse handoff = createHandoff(TENANT_A, "postgres-demo-complete");

    AiWorkSuggestionResponse suggestion =
        aiWorkMapper.toResponse(
            aiWorkService.createSuggestion(
                AiWorkType.NEXT_ACTION_SUGGESTION,
                AiWorkSourceType.RFQ_HANDOFF,
                handoff.id(),
                handoff.requestText(),
                "postgres-ai:" + handoff.id(),
                actorId));
    handoffService.startReview(handoff.id(), actorId);

    var firstDraft =
        bridgeService.createDraftQuote(
            handoff.id(), actorId, ActorRole.SALES_QUOTE_MANAGER);
    var retriedDraft =
        bridgeService.createDraftQuote(
            handoff.id(), actorId, ActorRole.SALES_QUOTE_MANAGER);

    RfqHandoffDecisionRequest request =
        new RfqHandoffDecisionRequest(
            "COMPLETE_DEMO", "PostgreSQL proof: reviewed; no external action requested.");
    RfqHandoffDecisionResponse firstDecision =
        decideIdempotently(handoff.id(), actorId, "postgres-demo-decision", request);
    RfqHandoffDecisionResponse retriedDecision =
        decideIdempotently(handoff.id(), actorId, "postgres-demo-decision", request);

    assertThat(suggestion.summary()).isNotBlank();
    assertThat(suggestion.advisoryOnly()).isTrue();
    assertThat(retriedDraft.draftQuote().id()).isEqualTo(firstDraft.draftQuote().id());
    assertThat(draftQuoteRepository.countByTenantId(TENANT_A)).isEqualTo(1);
    assertThat(firstDecision).isEqualTo(retriedDecision);
    assertThat(firstDecision.quoteState()).isEqualTo("DEMO_COMPLETED");
    assertThat(firstDecision.terminalState()).isEqualTo("SAFE_DEMO_TERMINAL");
    assertThat(firstDecision.auditStatus()).isEqualTo("RECORDED");
    assertThat(firstDecision.externalExecution()).isEqualTo("DISABLED");
    assertThat(firstDecision.connectorAction()).isEqualTo("NOT_INVOKED");
    assertThat(firstDecision.outboxStatus()).isEqualTo("NOT_REQUESTED");
    assertThat(
            draftQuoteRepository
                .findByTenantIdAndIdempotencyKey(
                    TENANT_A, "rfq-handoff-draft-quote:" + handoff.id())
                .orElseThrow()
                .getStatus())
        .isEqualTo("DEMO_COMPLETED");
    assertThat(decisionAuditCount(TENANT_A)).isEqualTo(1);

    assertThatThrownBy(
            () ->
                bridgeService.decide(
                    handoff.id(),
                    actorId,
                    ActorRole.SALES_QUOTE_MANAGER,
                    "DECLINE_DEMO",
                    "Conflicting second terminal decision"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not in a valid state");
    assertThat(
            draftQuoteRepository
                .findByIdAndTenantId(firstDraft.draftQuote().id(), TENANT_A)
                .orElseThrow()
                .getStatus())
        .isEqualTo("DEMO_COMPLETED");
    assertThat(decisionAuditCount(TENANT_A)).isEqualTo(1);
    assertNoExternalWriteState();
  }

  @Test
  void deniedRoleAndCrossTenantDecisionDoNotMutatePostgresState() {
    UUID actorId = USER_A;
    TenantContext.setTenantId(TENANT_A);
    ChannelRfqHandoffResponse handoff = createHandoff(TENANT_A, "postgres-demo-denied");
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
                    "Wrong role must not mutate"))
        .isInstanceOf(TenantPolicyException.class);

    TenantContext.setTenantId(TENANT_B);
    assertThatThrownBy(
            () ->
                bridgeService.decide(
                    handoff.id(),
                    UUID.randomUUID(),
                    ActorRole.SALES_QUOTE_MANAGER,
                    "COMPLETE_DEMO",
                    "Cross-tenant attempt must not mutate"))
        .isInstanceOf(NotFoundException.class);

    TenantContext.setTenantId(TENANT_A);
    assertThat(
            draftQuoteRepository
                .findByIdAndTenantId(draft.draftQuote().id(), TENANT_A)
                .orElseThrow()
                .getStatus())
        .isNotIn("DEMO_COMPLETED", "DEMO_DECLINED");
    assertThat(decisionAuditCount(TENANT_A)).isZero();
    assertThat(draftQuoteRepository.countByTenantId(TENANT_B)).isZero();
    assertNoExternalWriteState();
  }

  @Test
  void browserStartedManagedIntakeIsTenantScopedAndReplaySafeOnPostgres() {
    Instant now = Instant.parse("2026-07-04T00:00:00Z");
    TenantContext.setTenantId(TENANT_A);
    ChannelConnection connection =
        new ChannelConnection(
            TENANT_A,
            ChannelProviderType.TELEGRAM,
            "Deterministic local demo RFQ source",
            "operant-local-demo",
            null,
            null,
            now);
    connection.activate(now);
    connectionRepository.save(connection);

    var first = localDemoRfqIntakeService.createOrGet(USER_A);
    var replay = localDemoRfqIntakeService.createOrGet(USER_A);

    assertThat(first.handoffId()).isEqualTo(replay.handoffId());
    assertThat(first.status()).isEqualTo("PENDING_REVIEW");
    assertThat(eventRepository.findByTenantIdOrderByReceivedAtDesc(TENANT_A))
        .hasSize(1);
    assertThat(channelRfqHandoffRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_A))
        .hasSize(1);
    assertThat(botRfqRequestRepository.findAll()).hasSize(1);

    TenantContext.setTenantId(TENANT_B);
    assertThatThrownBy(
            () -> localDemoRfqIntakeService.createOrGet(UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class);
    assertThat(eventRepository.findByTenantIdOrderByReceivedAtDesc(TENANT_B))
        .isEmpty();
    assertThat(channelRfqHandoffRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_B))
        .isEmpty();
    assertNoExternalWriteState();
  }

  private RfqHandoffDecisionResponse decideIdempotently(
      UUID handoffId,
      UUID actorId,
      String idempotencyKey,
      RfqHandoffDecisionRequest request) {
    return idempotencyService.execute(
        TENANT_A,
        actorId,
        idempotencyKey,
        "RFQ_HANDOFF_DEMO_DECISION",
        "DRAFT_QUOTE",
        handoffId.toString(),
        request,
        RfqHandoffDecisionResponse.class,
        () ->
            RfqHandoffDecisionResponse.from(
                bridgeService.decide(
                    handoffId,
                    actorId,
                    ActorRole.SALES_QUOTE_MANAGER,
                    request.decision(),
                    request.note())));
  }

  private ChannelRfqHandoffResponse createHandoff(UUID tenantId, String externalEventId) {
    Instant now = Instant.parse("2026-07-03T00:00:00Z");
    ChannelConnection connection =
        connectionRepository.save(
            new ChannelConnection(
                tenantId,
                ChannelProviderType.TELEGRAM,
                "PostgreSQL proof channel",
                "demo-account",
                null,
                null,
                now));
    InboundChannelEvent event =
        eventRepository.save(
            new InboundChannelEvent(
                tenantId,
                connection.getId(),
                ChannelProviderType.TELEGRAM,
                externalEventId,
                "CUSTOMER",
                "demo-sender",
                DEMO_RFQ,
                "hash-" + externalEventId,
                "{}",
                now));
    return handoffService.createFromChannelEvent(
        new CreateChannelRfqHandoffCommand(
            event.getId(),
            connection.getId(),
            "TELEGRAM",
            externalEventId,
            event.getSourceActorExternalId(),
            null,
            null,
            DEMO_RFQ,
            "RFQ_REQUEST"));
  }

  private long decisionAuditCount(UUID tenantId) {
    return auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId).stream()
        .filter(event -> "RFQ_HANDOFF_DEMO_DECISION_RECORDED".equals(event.getAction()))
        .count();
  }

  private void assertNoExternalWriteState() {
    assertThat(connectorCommandRepository.count()).isZero();
    assertThat(sandboxExecutionRepository.count()).isZero();
    assertThat(compensationPlanRepository.count()).isZero();
    assertThat(changeRequestRepository.count()).isZero();
    assertThat(outboxEventRepository.count()).isZero();
  }
}
