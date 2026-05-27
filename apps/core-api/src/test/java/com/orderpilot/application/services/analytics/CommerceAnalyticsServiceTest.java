package com.orderpilot.application.services.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.Stage8Dtos.CommerceAnalyticsSummaryResponse;
import com.orderpilot.api.dto.Stage8Dtos.AnalyticsOverviewResponse;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.bot.*;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.extraction.ExtractedLineItem;
import com.orderpilot.domain.extraction.ExtractedLineItemRepository;
import com.orderpilot.domain.extraction.ExtractionResult;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.extraction.ExtractionRun;
import com.orderpilot.domain.extraction.ExtractionRunRepository;
import com.orderpilot.domain.integration.ChangeRequestRepository;
import com.orderpilot.domain.integration.ConnectorCommandRepository;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.intake.InboundDocument;
import com.orderpilot.domain.intake.InboundDocumentRepository;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.domain.intake.WebhookEvent;
import com.orderpilot.domain.intake.WebhookEventRepository;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.reconciliation.*;
import com.orderpilot.domain.validation.ApprovalRequirement;
import com.orderpilot.domain.validation.ApprovalRequirementRepository;
import com.orderpilot.domain.validation.ValidationIssue;
import com.orderpilot.domain.validation.ValidationIssueRepository;
import com.orderpilot.domain.validation.ValidationRun;
import com.orderpilot.domain.validation.ValidationRunRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.domain.workspace.ExceptionCase;
import com.orderpilot.domain.workspace.ExceptionCaseRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
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
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CommerceAnalyticsService.class, CoreConfiguration.class})
class CommerceAnalyticsServiceTest {
  @Autowired private CommerceAnalyticsService service;
  @Autowired private BotMessageRepository botMessageRepository;
  @Autowired private BotRfqRequestRepository botRfqRequestRepository;
  @Autowired private BotConversationRepository botConversationRepository;
  @Autowired private BotHandoffRepository botHandoffRepository;
  @Autowired private ReconciliationCaseRepository caseRepository;
  @Autowired private InboundDocumentRepository inboundDocumentRepository;
  @Autowired private ChannelMessageRepository channelMessageRepository;
  @Autowired private WebhookEventRepository webhookEventRepository;
  @Autowired private ProcessingJobRepository processingJobRepository;
  @Autowired private ExtractionRunRepository extractionRunRepository;
  @Autowired private ExtractionResultRepository extractionResultRepository;
  @Autowired private ExtractedLineItemRepository extractedLineItemRepository;
  @Autowired private ValidationRunRepository validationRunRepository;
  @Autowired private ValidationIssueRepository validationIssueRepository;
  @Autowired private ApprovalRequirementRepository approvalRequirementRepository;
  @Autowired private ExceptionCaseRepository exceptionCaseRepository;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private DraftOrderRepository draftOrderRepository;
  @Autowired private ConnectorCommandRepository connectorCommandRepository;
  @Autowired private ChangeRequestRepository changeRequestRepository;
  @Autowired private CustomerAccountRepository customerAccountRepository;
  @Autowired private InventorySnapshotRepository inventorySnapshotRepository;
  @Autowired private PriceRuleRepository priceRuleRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void analyticsSummaryIsTenantIsolated() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID messageId = botMessageRepository.save(new BotMessage(tenantA, conversationId, "TELEGRAM", "a-chat", "a-msg", "Need quote", BotIntent.RFQ_REQUEST, "RECEIVED", true, Instant.parse("2026-05-18T00:00:00Z"))).getId();
    botRfqRequestRepository.save(new BotRfqRequest(tenantA, conversationId, messageId, "TELEGRAM", "Need quote", "Need quote", Instant.parse("2026-05-18T00:00:00Z")));
    caseRepository.save(new ReconciliationCase(tenantA, productId, locationId, new BigDecimal("116"), new BigDecimal("100"), new BigDecimal("-16"), ReconciliationSeverity.HIGH, "[\"stock count below expected\"]", Instant.parse("2026-05-18T00:00:00Z")));
    botMessageRepository.save(new BotMessage(tenantB, UUID.randomUUID(), "TELEGRAM", "b-chat", "b-msg", "Need quote", BotIntent.RFQ_REQUEST, "RECEIVED", true, Instant.parse("2026-05-18T00:00:00Z")));

    TenantContext.setTenantId(tenantA);
    CommerceAnalyticsSummaryResponse summary = service.summary();

    assertThat(summary.totalBotRfqRequests()).isEqualTo(1);
    assertThat(summary.openReconciliationCases()).isEqualTo(1);
    assertThat(summary.highSeverityReconciliationCases()).isEqualTo(1);
    assertThat(summary.channelBreakdown()).containsEntry("TELEGRAM", 1L);
  }

  @Test
  void analyticsOverviewAggregatesReadOnlyOperationalMetrics() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Instant now = Instant.parse("2026-05-18T00:00:00Z");
    inboundDocumentRepository.save(new InboundDocument(tenantId, "FILE_UPLOAD", "PDF", "RECEIVED", "rfq.pdf", "application/pdf", 10L, "obj", "sha", "buyer", "RFQ", "{}", now));
    channelMessageRepository.save(new ChannelMessage(tenantId, "TELEGRAM", "msg-1", "chat-1", "sender", "Sender", null, "INBOUND", "TEXT", "Need quote", "{}", "PENDING_REVIEW_UNLINKED_IDENTITY", now));
    webhookEventRepository.save(new WebhookEvent(tenantId, "TELEGRAM", "evt-1", true, true, "{}", "{}", "REPLAY_REJECTED", now));
    ProcessingJob stale = processingJobRepository.save(new ProcessingJob(tenantId, "MESSAGE_PROCESSING", "CHANNEL_MESSAGE", UUID.randomUUID(), 5, now.minusSeconds(90_000)));
    ReflectionTestUtils.setField(stale, "status", "FAILED");
    processingJobRepository.save(stale);
    ExtractionRun extractionRun = extractionRunRepository.save(new ExtractionRun(tenantId, "CHANNEL_MESSAGE", UUID.randomUUID(), null, "MOCK", "stub", "none", "stage-4", "v1", now));
    extractionRun.markSucceeded(now);
    extractionRunRepository.save(extractionRun);
    ExtractionResult extractionResult = extractionResultRepository.save(new ExtractionResult(tenantId, extractionRun.getId(), "CHANNEL_MESSAGE", UUID.randomUUID(), "RFQ", "message", new BigDecimal("0.40"), "{}", "needs_review", now));
    extractedLineItemRepository.save(new ExtractedLineItem(tenantId, extractionResult.getId(), 1, "SKU", "Desc", "1", BigDecimal.ONE, "EA", "EA", new BigDecimal("0.40"), null, now));
    ValidationRun validationRun = validationRunRepository.save(new ValidationRun(tenantId, extractionResult.getId(), "FULL", now));
    validationRun.complete("NEEDS_REVIEW", new BigDecimal("0.40"), now);
    validationRunRepository.save(validationRun);
    validationIssueRepository.save(new ValidationIssue(tenantId, validationRun.getId(), extractionResult.getId(), null, null, "PRODUCT_NOT_FOUND", "ERROR", "No product", "{}", now));
    approvalRequirementRepository.save(new ApprovalRequirement(tenantId, validationRun.getId(), null, "NEEDS_HUMAN_REVIEW", "WARNING", "Low confidence", now));
    exceptionCaseRepository.save(new ExceptionCase(tenantId, "CASE-1", "VALIDATION_RUN", validationRun.getId(), extractionResult.getId(), validationRun.getId(), null, "Review", "ESCALATED", "HIGH", "ERROR", "Needs operator", now));
    BotConversation conversation = botConversationRepository.save(new BotConversation(tenantId, "TELEGRAM", "chat-1", now));
    conversation.touch("HUMAN_REVIEW", true, now);
    botConversationRepository.save(conversation);
    UUID botMessageId = botMessageRepository.save(new BotMessage(tenantId, conversation.getId(), "TELEGRAM", "chat-1", "bot-msg-1", "Unknown", BotIntent.UNKNOWN, "RECEIVED", true, now)).getId();
    botHandoffRepository.save(new BotHandoff(tenantId, conversation.getId(), botMessageId, "TELEGRAM", "UNKNOWN_INTENT", now));
    long quotesBefore = draftQuoteRepository.count();
    long ordersBefore = draftOrderRepository.count();
    long connectorsBefore = connectorCommandRepository.count();
    long changesBefore = changeRequestRepository.count();
    long customersBefore = customerAccountRepository.count();
    long inventoryBefore = inventorySnapshotRepository.count();
    long pricesBefore = priceRuleRepository.count();

    AnalyticsOverviewResponse overview = service.overview();

    assertThat(overview.intake().totalInboundDocuments()).isEqualTo(1);
    assertThat(overview.intake().totalChannelMessages()).isEqualTo(1);
    assertThat(overview.intake().volumeByChannel()).containsEntry("TELEGRAM", 1L).containsEntry("FILE_UPLOAD", 1L);
    assertThat(overview.extraction().lowConfidenceExtractionCount()).isEqualTo(1);
    assertThat(overview.extraction().extractedLineItemCount()).isEqualTo(1);
    assertThat(overview.validation().topIssueCodes()).containsEntry("PRODUCT_NOT_FOUND", 1L);
    assertThat(overview.validation().approvalRequirementsByReason()).containsEntry("NEEDS_HUMAN_REVIEW", 1L);
    assertThat(overview.review().escalatedCases()).isEqualTo(1);
    assertThat(overview.bot().unknownIntentCount()).isEqualTo(1);
    assertThat(overview.bot().handoffCount()).isEqualTo(1);
    assertThat(overview.workflowHealth().failedJobs()).isEqualTo(1);
    assertThat(draftQuoteRepository.count()).isEqualTo(quotesBefore);
    assertThat(draftOrderRepository.count()).isEqualTo(ordersBefore);
    assertThat(connectorCommandRepository.count()).isEqualTo(connectorsBefore);
    assertThat(changeRequestRepository.count()).isEqualTo(changesBefore);
    assertThat(customerAccountRepository.count()).isEqualTo(customersBefore);
    assertThat(inventorySnapshotRepository.count()).isEqualTo(inventoryBefore);
    assertThat(priceRuleRepository.count()).isEqualTo(pricesBefore);
  }
}
