package com.orderpilot.application.services.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.bot.BotConversation;
import com.orderpilot.domain.bot.BotConversationRepository;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.intake.InboundDocument;
import com.orderpilot.domain.intake.InboundDocumentRepository;
import com.orderpilot.domain.validation.ValidationIssue;
import com.orderpilot.domain.validation.ValidationIssueRepository;
import com.orderpilot.domain.workspace.DraftOrder;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.domain.workspace.ExceptionCase;
import com.orderpilot.domain.workspace.ExceptionCaseRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
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
@Import({CommerceAnalyticsService.class, CoreConfiguration.class})
class CommerceAnalyticsServiceStage8Test {
  private static final Instant NOW = Instant.parse("2026-05-26T00:00:00Z");

  @Autowired private CommerceAnalyticsService service;
  @Autowired private ChannelMessageRepository channelMessages;
  @Autowired private InboundDocumentRepository inboundDocuments;
  @Autowired private ExceptionCaseRepository exceptionCases;
  @Autowired private AuditEventRepository auditEvents;
  @Autowired private DraftQuoteRepository draftQuotes;
  @Autowired private DraftOrderRepository draftOrders;
  @Autowired private ValidationIssueRepository validationIssues;
  @Autowired private BotConversationRepository botConversations;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void commandCenterSeparatesBotOnlyHandoffsFromValidationBackedReviewsAndUnsafeAttempts() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID validationRunId = UUID.randomUUID();
    UUID extractionResultId = UUID.randomUUID();
    ExceptionCase validationCase = exceptionCases.save(new ExceptionCase(tenantId, "VAL-1", "VALIDATION_RUN", validationRunId, extractionResultId, validationRunId, null, "Validation review", "RESOLVED", "HIGH", "ERROR", "resolved", NOW.minusSeconds(7200)));
    validationCase.setStatus("RESOLVED", NOW);
    exceptionCases.save(validationCase);
    ExceptionCase botCase = exceptionCases.save(new ExceptionCase(tenantId, "BOT-1", "BOT_CONVERSATION", UUID.randomUUID(), null, null, null, "Bot handoff", "OPEN", "NORMAL", "INFO", "bot-only", NOW));
    auditEvents.save(new AuditEvent(tenantId, null, "DRAFT_PREPARATION_BLOCKED", "DRAFT_QUOTE", botCase.getId().toString(), "{\"reason\":\"BOT_HANDOFF_NOT_VALIDATION_BACKED\"}", NOW));
    channelMessages.save(new ChannelMessage(tenantId, "TELEGRAM", "msg-1", "chat-1", "sender", "Sender", null, "INBOUND", "TEXT", "Need quote", "{}", "RECEIVED", NOW));
    inboundDocuments.save(new InboundDocument(tenantId, "EMAIL", "PDF", "RECEIVED", "rfq.pdf", "application/pdf", 10L, "obj", "sha", "buyer", "RFQ", "{}", NOW));
    draftQuotes.save(new DraftQuote(tenantId, "Q-1", null, extractionResultId, validationRunId, validationCase.getId(), "DRAFT", "USD", null, NOW));
    draftOrders.save(new DraftOrder(tenantId, "O-1", null, extractionResultId, validationRunId, validationCase.getId(), "DRAFT", "USD", null, NOW));
    validationIssues.save(new ValidationIssue(tenantId, validationRunId, extractionResultId, null, null, "MARGIN_BELOW_GUARDRAIL", "ERROR", "Margin risk", "{}", NOW));
    validationIssues.save(new ValidationIssue(tenantId, validationRunId, extractionResultId, null, null, "DISCOUNT_REQUIRES_APPROVAL", "WARNING", "Discount risk", "{}", NOW));

    var commandCenter = service.stage8CommandCenter();
    var review = service.stage8OperatorReview();
    var bot = service.stage8BotHandoffs();

    assertThat(commandCenter.totalInboundRequests()).isEqualTo(2);
    assertThat(commandCenter.botOnlyHandoffCount()).isEqualTo(1);
    assertThat(commandCenter.validationBackedReviewCount()).isEqualTo(1);
    assertThat(commandCenter.blockedUnsafeDraftAttempts()).isEqualTo(1);
    assertThat(commandCenter.draftsPrepared()).isEqualTo(2);
    assertThat(commandCenter.channelMix()).containsEntry("TELEGRAM", 1L).containsEntry("EMAIL", 1L);
    assertThat(review.averageReviewCycleHours()).isEqualByComparingTo("2.00");
    assertThat(review.marginRiskCount()).isEqualTo(1);
    assertThat(review.discountRiskCount()).isEqualTo(1);
    assertThat(bot.botOnlyHandoffCount()).isEqualTo(1);
    assertThat(bot.blockedBotOnlyDraftPreparationCount()).isEqualTo(1);
  }

  @Test
  void stage8AnalyticsAreTenantIsolated() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    BotConversation conversation = botConversations.save(new BotConversation(tenantA, "TELEGRAM", "a-chat", NOW));
    ExceptionCase botCase = exceptionCases.save(new ExceptionCase(tenantA, "BOT-A", "BOT_CONVERSATION", conversation.getId(), null, null, null, "Bot A", "OPEN", "NORMAL", "INFO", "tenant A", NOW));
    auditEvents.save(new AuditEvent(tenantA, null, "DRAFT_PREPARATION_BLOCKED", "DRAFT_QUOTE", botCase.getId().toString(), "{}", NOW));
    channelMessages.save(new ChannelMessage(tenantB, "WHATSAPP", "msg-b", "chat-b", "sender", "Sender", null, "INBOUND", "TEXT", "Other tenant", "{}", "RECEIVED", NOW));
    exceptionCases.save(new ExceptionCase(tenantB, "BOT-B", "BOT_CONVERSATION", UUID.randomUUID(), null, null, null, "Bot B", "OPEN", "NORMAL", "INFO", "tenant B", NOW));

    assertThat(service.stage8BotHandoffs().botOnlyHandoffCount()).isEqualTo(1);
    assertThat(service.stage8BotHandoffs().blockedBotOnlyDraftPreparationCount()).isEqualTo(1);
    assertThat(service.stage8ChannelVolume().requestVolumeByChannel()).doesNotContainKey("WHATSAPP");

    TenantContext.setTenantId(tenantB);
    assertThat(service.stage8BotHandoffs().botOnlyHandoffCount()).isEqualTo(1);
    assertThat(service.stage8BotHandoffs().blockedBotOnlyDraftPreparationCount()).isZero();
    assertThat(service.stage8ChannelVolume().requestVolumeByChannel()).containsEntry("WHATSAPP", 1L);
  }
}
