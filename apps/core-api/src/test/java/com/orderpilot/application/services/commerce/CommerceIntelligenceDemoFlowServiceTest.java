package com.orderpilot.application.services.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.CommerceIntelligenceDtos.CommerceIntelligenceDemoFlowResponse;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.common.tenant.TenantContextMissingException;
import com.orderpilot.domain.aiwork.AiWorkSourceType;
import com.orderpilot.domain.aiwork.AiWorkSuggestion;
import com.orderpilot.domain.aiwork.AiWorkSuggestionRepository;
import com.orderpilot.domain.aiwork.AiWorkType;
import com.orderpilot.domain.channel.ChannelRfqHandoff;
import com.orderpilot.domain.channel.ChannelRfqHandoffRepository;
import com.orderpilot.domain.channel.ChannelRfqHandoffStatus;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.domain.workspace.QuoteValidationIssue;
import com.orderpilot.domain.workspace.QuoteValidationIssueRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CommerceIntelligenceDemoFlowServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-05T12:00:00Z");
  @Mock private ChannelRfqHandoffRepository handoffRepository;
  @Mock private AiWorkSuggestionRepository aiSuggestionRepository;
  @Mock private DraftQuoteRepository draftQuoteRepository;
  @Mock private QuoteValidationIssueRepository issueRepository;
  private CommerceIntelligenceDemoFlowService service;

  @BeforeEach
  void setUp() {
    service =
        new CommerceIntelligenceDemoFlowService(
            handoffRepository,
            aiSuggestionRepository,
            draftQuoteRepository,
            issueRepository,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void validDemoFlowReturnsTenantScopedSummarySafetyRuntimeAndRecentFlow() throws Exception {
    UUID tenantId = UUID.randomUUID();
    UUID handoffId = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    ChannelRfqHandoff handoff = convertedHandoff(tenantId, handoffId);
    AiWorkSuggestion suggestion = suggestion(tenantId, handoffId);
    DraftQuote quote = demoCompletedQuote(tenantId, handoffId, quoteId);
    QuoteValidationIssue issue =
        new QuoteValidationIssue(
            tenantId,
            quoteId,
            null,
            "PRICE_NOT_RESOLVED",
            "ERROR",
            true,
            "Internal message is not returned.",
            "{\"rawPayload\":\"not returned\"}",
            NOW.minusSeconds(60));
    ReflectionTestUtils.setField(issue, "id", UUID.randomUUID());

    when(handoffRepository.countByTenantId(tenantId)).thenReturn(4L);
    when(handoffRepository.countByTenantIdAndStatus(
            tenantId, ChannelRfqHandoffStatus.PENDING_REVIEW))
        .thenReturn(1L);
    when(handoffRepository.countByTenantIdAndStatus(
            tenantId, ChannelRfqHandoffStatus.IN_REVIEW))
        .thenReturn(1L);
    when(handoffRepository.countByTenantIdAndStatus(
            tenantId, ChannelRfqHandoffStatus.CONVERTED))
        .thenReturn(1L);
    when(handoffRepository.countByTenantIdAndStatus(
            tenantId, ChannelRfqHandoffStatus.DISMISSED))
        .thenReturn(1L);
    when(aiSuggestionRepository.countByTenantIdAndSourceType(tenantId, "RFQ_HANDOFF"))
        .thenReturn(1L);
    when(draftQuoteRepository.countByTenantIdAndSourceTypeAndRequiresHumanReviewTrue(
            tenantId, "RFQ_HANDOFF"))
        .thenReturn(1L);
    when(draftQuoteRepository.countByTenantIdAndSourceTypeAndStatusIn(
            tenantId, "RFQ_HANDOFF", Set.of("DEMO_COMPLETED", "DEMO_DECLINED")))
        .thenReturn(1L);
    when(draftQuoteRepository.countByTenantIdAndSourceTypeAndStatus(
            tenantId, "RFQ_HANDOFF", "DEMO_COMPLETED"))
        .thenReturn(1L);
    when(draftQuoteRepository.countByTenantIdAndSourceTypeAndStatus(
            tenantId, "RFQ_HANDOFF", "DEMO_DECLINED"))
        .thenReturn(0L);
    when(handoffRepository.findByTenantIdOrderByCreatedAtDescIdDesc(
            eq(tenantId), any(Pageable.class)))
        .thenReturn(List.of(handoff));
    when(aiSuggestionRepository
            .findByTenantIdAndSourceTypeAndSourceIdInOrderByCreatedAtDesc(
                eq(tenantId), eq("RFQ_HANDOFF"), eq(List.of(handoffId)), any(Pageable.class)))
        .thenReturn(List.of(suggestion));
    when(draftQuoteRepository.findByTenantIdAndIdempotencyKeyIn(
            eq(tenantId), anyCollection()))
        .thenReturn(List.of(quote));
    when(issueRepository
            .findByTenantIdAndDraftQuoteIdInAndBlockingTrueAndStatusOrderByCreatedAtAsc(
                eq(tenantId), eq(List.of(quoteId)), eq("OPEN"), any(Pageable.class)))
        .thenReturn(List.of(issue));
    QuoteValidationIssueRepository.BlockingIssueAggregate aggregate =
        mock(QuoteValidationIssueRepository.BlockingIssueAggregate.class);
    when(aggregate.getCode()).thenReturn("PRICE_NOT_RESOLVED");
    when(aggregate.getTotal()).thenReturn(1L);
    when(issueRepository.summarizeOpenBlockingIssuesForSourceType(
            eq(tenantId), eq("RFQ_HANDOFF"), any(Pageable.class)))
        .thenReturn(List.of(aggregate));

    CommerceIntelligenceDemoFlowResponse response = service.readDemoFlow();

    assertThat(response.generatedAt()).isEqualTo(NOW);
    assertThat(response.summary().rfqHandoffsTotal()).isEqualTo(4);
    assertThat(response.summary().safeTerminalDemoDecisionsCount()).isEqualTo(1);
    assertThat(response.summary().demoCompletedCount()).isEqualTo(1);
    assertThat(response.safety().externalExecutionStatus()).isEqualTo("DISABLED");
    assertThat(response.safety().observedConnectorCommandRows()).isNull();
    assertThat(response.safety().measurementScope()).isEqualTo("NOT_MEASURED");
    assertThat(response.runtimeControl().guarded()).isTrue();
    assertThat(response.runtimeControl().rfqHandoffAiAdvisory())
        .isEqualTo("AI_VALIDATION_EXPLANATION_GUARDED");
    assertThat(response.runtimeControl().billingOrQuotaDimension())
        .isEqualTo("NOT_APPLICABLE_FOR_DEMO_OPS");
    assertThat(response.runtimeControl().denialTelemetry()).isEqualTo("NOT_MEASURED");
    assertThat(response.bottlenecks()).singleElement().satisfies(
        bottleneck -> {
          assertThat(bottleneck.code()).isEqualTo("PRICE_NOT_RESOLVED");
          assertThat(bottleneck.count()).isEqualTo(1);
        });
    assertThat(response.recentFlows()).singleElement().satisfies(
        flow -> {
          assertThat(flow.handoffId()).isEqualTo(handoffId);
          assertThat(flow.aiSchemaVersion())
              .isEqualTo("AI_WORK_SCHEMA_V1_NEXT_ACTION_SUGGESTION");
          assertThat(flow.draftQuoteStatus()).isEqualTo("DEMO_COMPLETED");
          assertThat(flow.safeTerminalState()).isEqualTo("SAFE_DEMO_TERMINAL");
          assertThat(flow.blockingIssueCodes()).containsExactly("PRICE_NOT_RESOLVED");
          assertThat(flow.requestPreview()).hasSizeLessThanOrEqualTo(180);
        });

    String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
    assertThat(json)
        .doesNotContain(
            "\"tenantId\"",
            "\"actorId\"",
            "\"reviewerUserId\"",
            "\"createdByUserId\"",
            "\"decidedByUserId\"",
            "\"idempotencyKey\"",
            "\"auditId\"",
            "\"correlationId\"",
            "\"inboundChannelEventId\"",
            "\"channelConnectionId\"",
            "\"rawPayload\"",
            "\"payloadJson\"",
            "\"prompt\"",
            "\"token\"",
            "\"secret\"",
            "\"credential\"",
            "\"stackTrace\"",
            "\"quotaBucket\"",
            "\"redisKey\"",
            "\"jti\"",
            "\"nonce\"",
            "\"retryAfterSeconds\"");
  }

  @Test
  void everyReadUsesCurrentTenantAndNoRepositoryMutationIsInvoked() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);

    service.readDemoFlow();

    verify(handoffRepository).countByTenantId(tenantA);
    verify(aiSuggestionRepository).countByTenantIdAndSourceType(tenantA, "RFQ_HANDOFF");
    verify(draftQuoteRepository)
        .countByTenantIdAndSourceTypeAndRequiresHumanReviewTrue(tenantA, "RFQ_HANDOFF");
    verify(issueRepository)
        .summarizeOpenBlockingIssuesForSourceType(
            eq(tenantA), eq("RFQ_HANDOFF"), any(Pageable.class));
    verify(handoffRepository)
        .findByTenantIdOrderByCreatedAtDescIdDesc(eq(tenantA), any(Pageable.class));

    verify(handoffRepository, never()).countByTenantId(tenantB);
    verify(handoffRepository, never()).save(any());
    verify(aiSuggestionRepository, never()).save(any());
    verify(draftQuoteRepository, never()).save(any());
    verify(issueRepository, never()).save(any());
  }

  @Test
  void missingTenantContextFailsBeforeRepositoryAccess() {
    assertThatThrownBy(service::readDemoFlow).isInstanceOf(TenantContextMissingException.class);

    verify(handoffRepository, never()).countByTenantId(any());
    verify(aiSuggestionRepository, never()).countByTenantIdAndSourceType(any(), any());
    verify(draftQuoteRepository, never()).countByTenantIdAndSourceType(any(), any());
  }

  private static ChannelRfqHandoff convertedHandoff(UUID tenantId, UUID handoffId) {
    String longRequest = "Please quote ".repeat(40);
    ChannelRfqHandoff handoff =
        new ChannelRfqHandoff(
            tenantId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "TELEGRAM",
            "provider-event-not-exposed",
            "sender-not-exposed-by-this-read-model",
            null,
            null,
            longRequest,
            "RFQ_REQUEST",
            NOW.minusSeconds(300));
    ReflectionTestUtils.setField(handoff, "id", handoffId);
    UUID operator = UUID.randomUUID();
    handoff.startReview(operator, NOW.minusSeconds(240));
    handoff.markConverted("Internal conversion note is not exposed.", operator, NOW.minusSeconds(180));
    return handoff;
  }

  private static AiWorkSuggestion suggestion(UUID tenantId, UUID handoffId) {
    AiWorkSuggestion suggestion =
        new AiWorkSuggestion(
            tenantId,
            AiWorkType.NEXT_ACTION_SUGGESTION,
            AiWorkSourceType.RFQ_HANDOFF,
            handoffId,
            "deterministic-v1",
            "MEDIUM",
            new BigDecimal("0.80"),
            "Provider text is not exposed by this read model.",
            "{\"schemaVersion\":\"AI_WORK_SCHEMA_V1_NEXT_ACTION_SUGGESTION\"}",
            "[]",
            "internal-key-not-exposed",
            UUID.randomUUID(),
            NOW.minusSeconds(170));
    ReflectionTestUtils.setField(suggestion, "id", UUID.randomUUID());
    return suggestion;
  }

  private static DraftQuote demoCompletedQuote(
      UUID tenantId, UUID handoffId, UUID quoteId) {
    DraftQuote quote =
        new DraftQuote(
            tenantId,
            "DQ-DEMO",
            "RFQ_HANDOFF",
            null,
            null,
            null,
            "Demo customer",
            "NEEDS_REVIEW",
            "NEEDS_REVIEW",
            true,
            "USD",
            UUID.randomUUID(),
            UUID.randomUUID(),
            NOW.minusSeconds(160));
    ReflectionTestUtils.setField(quote, "id", quoteId);
    quote.setIdempotencyKey("rfq-handoff-draft-quote:" + handoffId);
    quote.transition(
        "DEMO_COMPLETED", "NEEDS_REVIEW", true, UUID.randomUUID(), NOW.minusSeconds(120));
    return quote;
  }
}
