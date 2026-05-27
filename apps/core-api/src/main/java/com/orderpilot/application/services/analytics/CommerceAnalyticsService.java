package com.orderpilot.application.services.analytics;

import com.orderpilot.api.dto.Stage8Dtos.*;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.bot.*;
import com.orderpilot.domain.extraction.*;
import com.orderpilot.domain.intake.*;
import com.orderpilot.domain.reconciliation.ReconciliationCaseRepository;
import com.orderpilot.domain.reconciliation.ReconciliationSeverity;
import com.orderpilot.domain.reconciliation.ReconciliationStatus;
import com.orderpilot.domain.validation.*;
import com.orderpilot.domain.workspace.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommerceAnalyticsService {
  private final DraftOrderRepository draftOrderRepository;
  private final BotConversationRepository botConversationRepository;
  private final BotRfqRequestRepository botRfqRequestRepository;
  private final BotMessageRepository botMessageRepository;
  private final BotHandoffRepository botHandoffRepository;
  private final ReconciliationCaseRepository reconciliationCaseRepository;
  private final InboundDocumentRepository inboundDocumentRepository;
  private final ChannelMessageRepository channelMessageRepository;
  private final WebhookEventRepository webhookEventRepository;
  private final InboundEventLedgerRepository inboundEventLedgerRepository;
  private final ProcessingJobRepository processingJobRepository;
  private final ExtractionRunRepository extractionRunRepository;
  private final ExtractionResultRepository extractionResultRepository;
  private final ExtractedLineItemRepository extractedLineItemRepository;
  private final ValidationRunRepository validationRunRepository;
  private final ValidationIssueRepository validationIssueRepository;
  private final ApprovalRequirementRepository approvalRequirementRepository;
  private final ExceptionCaseRepository exceptionCaseRepository;
  private final DraftQuoteRepository draftQuoteRepository;
  private final OperatorActionRepository operatorActionRepository;
  private final AuditEventRepository auditEventRepository;
  private final Clock clock;

  public CommerceAnalyticsService(DraftOrderRepository draftOrderRepository, BotConversationRepository botConversationRepository, BotRfqRequestRepository botRfqRequestRepository, BotMessageRepository botMessageRepository, BotHandoffRepository botHandoffRepository, ReconciliationCaseRepository reconciliationCaseRepository, InboundDocumentRepository inboundDocumentRepository, ChannelMessageRepository channelMessageRepository, WebhookEventRepository webhookEventRepository, InboundEventLedgerRepository inboundEventLedgerRepository, ProcessingJobRepository processingJobRepository, ExtractionRunRepository extractionRunRepository, ExtractionResultRepository extractionResultRepository, ExtractedLineItemRepository extractedLineItemRepository, ValidationRunRepository validationRunRepository, ValidationIssueRepository validationIssueRepository, ApprovalRequirementRepository approvalRequirementRepository, ExceptionCaseRepository exceptionCaseRepository, DraftQuoteRepository draftQuoteRepository, OperatorActionRepository operatorActionRepository, AuditEventRepository auditEventRepository, Clock clock) {
    this.draftOrderRepository = draftOrderRepository;
    this.botConversationRepository = botConversationRepository;
    this.botRfqRequestRepository = botRfqRequestRepository;
    this.botMessageRepository = botMessageRepository;
    this.botHandoffRepository = botHandoffRepository;
    this.reconciliationCaseRepository = reconciliationCaseRepository;
    this.inboundDocumentRepository = inboundDocumentRepository;
    this.channelMessageRepository = channelMessageRepository;
    this.webhookEventRepository = webhookEventRepository;
    this.inboundEventLedgerRepository = inboundEventLedgerRepository;
    this.processingJobRepository = processingJobRepository;
    this.extractionRunRepository = extractionRunRepository;
    this.extractionResultRepository = extractionResultRepository;
    this.extractedLineItemRepository = extractedLineItemRepository;
    this.validationRunRepository = validationRunRepository;
    this.validationIssueRepository = validationIssueRepository;
    this.approvalRequirementRepository = approvalRequirementRepository;
    this.exceptionCaseRepository = exceptionCaseRepository;
    this.draftQuoteRepository = draftQuoteRepository;
    this.operatorActionRepository = operatorActionRepository;
    this.auditEventRepository = auditEventRepository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public CommerceAnalyticsSummaryResponse summary() {
    UUID tenantId = TenantContext.requireTenantId();
    return new CommerceAnalyticsSummaryResponse(
        tenantId,
        BigDecimal.ZERO,
        "TODO: totalSalesAmount remains 0 until invoice/sales mirror records are added.",
        draftOrderRepository.countByTenantId(tenantId),
        botRfqRequestRepository.countByTenantId(tenantId),
        reconciliationCaseRepository.countByTenantIdAndStatus(tenantId, ReconciliationStatus.OPEN),
        reconciliationCaseRepository.countByTenantIdAndSeverityAndStatus(tenantId, ReconciliationSeverity.HIGH, ReconciliationStatus.OPEN),
        channelBreakdown(tenantId),
        clock.instant());
  }

  @Transactional(readOnly = true)
  public AnalyticsOverviewResponse overview() {
    UUID tenantId = TenantContext.requireTenantId();
    Instant generatedAt = clock.instant();
    IntakeAnalyticsResponse intake = intake();
    ExtractionAnalyticsResponse extraction = extraction();
    ValidationAnalyticsResponse validation = validation();
    ReviewAnalyticsResponse review = review();
    BotAnalyticsResponse bot = bot();
    WorkflowHealthAnalyticsResponse workflowHealth = workflowHealth();
    return new AnalyticsOverviewResponse(tenantId, intake, extraction, validation, review, bot, workflowHealth, readiness(validation, review, bot, workflowHealth), generatedAt);
  }

  @Transactional(readOnly = true)
  public IntakeAnalyticsResponse intake() {
    UUID tenantId = TenantContext.requireTenantId();
    List<InboundDocument> documents = inboundDocumentRepository.findByTenantIdOrderByReceivedAtDesc(tenantId);
    List<ChannelMessage> messages = channelMessageRepository.findByTenantIdOrderByReceivedAtDesc(tenantId);
    List<WebhookEvent> webhooks = webhookEventRepository.findByTenantIdOrderByReceivedAtDesc(tenantId);
    List<InboundEventLedger> ledger = inboundEventLedgerRepository.findByTenantIdOrderByReceivedAtDesc(tenantId);
    List<ProcessingJob> jobs = processingJobRepository.findByTenantIdOrderByQueuedAtDesc(tenantId);
    Map<String, Long> volumeByChannel = new LinkedHashMap<>(channelBreakdown(tenantId));
    documents.stream().collect(Collectors.groupingBy(InboundDocument::getSourceChannel, LinkedHashMap::new, Collectors.counting())).forEach((channel, count) -> volumeByChannel.merge(channel, count, Long::sum));
    long duplicateOrReplayEvents = webhooks.stream().filter(WebhookEvent::isReplayDetected).count()
        + ledger.stream().filter(e -> "DUPLICATE".equals(e.getStatus()) || "REPLAY".equals(e.getStatus())).count()
        + documents.stream().filter(d -> "DUPLICATE".equals(d.getStatus())).count()
        + messages.stream().filter(m -> "DUPLICATE".equals(m.getStatus())).count();
    long backlog = jobs.stream().filter(j -> "PENDING".equals(j.getStatus()) || "RUNNING".equals(j.getStatus()) || "QUEUED".equals(j.getStatus())).count();
    return new IntakeAnalyticsResponse(tenantId, documents.size(), messages.size(), webhooks.size(), duplicateOrReplayEvents, backlog, volumeByChannel, countBy(jobs, ProcessingJob::getStatus), clock.instant());
  }

  @Transactional(readOnly = true)
  public ExtractionAnalyticsResponse extraction() {
    UUID tenantId = TenantContext.requireTenantId();
    List<ExtractionRun> runs = extractionRunRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    List<ExtractionResult> results = extractionResultRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    BigDecimal average = results.isEmpty() ? BigDecimal.ZERO : results.stream().map(ExtractionResult::getOverallConfidence).reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(results.size()), 4, RoundingMode.HALF_UP);
    long lowConfidence = results.stream().filter(r -> r.getOverallConfidence().compareTo(new BigDecimal("0.50")) < 0).count();
    long lineItems = results.stream().mapToLong(r -> extractedLineItemRepository.findByTenantIdAndExtractionResultId(tenantId, r.getId()).size()).sum();
    return new ExtractionAnalyticsResponse(tenantId, countBy(runs, ExtractionRun::getStatus), average, lowConfidence, lineItems, clock.instant());
  }

  @Transactional(readOnly = true)
  public ValidationAnalyticsResponse validation() {
    UUID tenantId = TenantContext.requireTenantId();
    List<ValidationRun> runs = validationRunRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    List<ValidationIssue> issues = validationIssueRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, "OPEN");
    List<ApprovalRequirement> approvals = runs.stream().flatMap(r -> approvalRequirementRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, r.getId()).stream()).toList();
    long blocked = runs.stream().filter(r -> "BLOCKED".equals(r.getOverallStatus()) || "FAILED".equals(r.getOverallStatus())).count();
    long needsReview = runs.stream().filter(r -> "NEEDS_REVIEW".equals(r.getOverallStatus())).count();
    long passed = runs.stream().filter(r -> "VALIDATION_PASSED".equals(r.getOverallStatus()) || "VALIDATION_PASSED_WITH_WARNINGS".equals(r.getOverallStatus())).count();
    return new ValidationAnalyticsResponse(tenantId, countBy(runs, ValidationRun::getOverallStatus), topCounts(issues, ValidationIssue::getIssueType), blocked, needsReview, passed, topCounts(approvals, ApprovalRequirement::getRequirementType), clock.instant());
  }

  @Transactional(readOnly = true)
  public ReviewAnalyticsResponse review() {
    UUID tenantId = TenantContext.requireTenantId();
    List<ExceptionCase> cases = exceptionCaseRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    long backlog = cases.stream().filter(c -> List.of("REVIEW_REQUIRED", "IN_REVIEW", "OPEN", "WAITING_APPROVAL").contains(c.getStatus())).count();
    return new ReviewAnalyticsResponse(tenantId, countBy(cases, ExceptionCase::getStatus), backlog, countStatus(cases, "ESCALATED"), countStatus(cases, "NEEDS_CORRECTION"), countStatus(cases, "APPROVED_FOR_NEXT_STEP"), clock.instant());
  }

  @Transactional(readOnly = true)
  public BotAnalyticsResponse bot() {
    UUID tenantId = TenantContext.requireTenantId();
    List<BotConversation> conversations = botConversationRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId);
    List<BotMessage> messages = conversations.stream().flatMap(c -> botMessageRepository.findByTenantIdAndConversationIdOrderByCreatedAtAsc(tenantId, c.getId()).stream()).toList();
    long handoffs = conversations.stream().mapToLong(c -> botHandoffRepository.findByTenantIdAndConversationIdOrderByCreatedAtDesc(tenantId, c.getId()).size()).sum();
    return new BotAnalyticsResponse(tenantId, countBy(conversations, BotConversation::getStatus), topCounts(messages, m -> m.getDetectedIntent().name()), handoffs, conversations.stream().filter(BotConversation::isRequiresHumanReview).count(), messages.stream().filter(m -> m.getDetectedIntent() == BotIntent.UNKNOWN).count(), clock.instant());
  }

  @Transactional(readOnly = true)
  public WorkflowHealthAnalyticsResponse workflowHealth() {
    UUID tenantId = TenantContext.requireTenantId();
    List<ProcessingJob> jobs = processingJobRepository.findByTenantIdOrderByQueuedAtDesc(tenantId);
    Instant staleBefore = clock.instant().minus(Duration.ofHours(24));
    long staleJobs = jobs.stream().filter(j -> ("PENDING".equals(j.getStatus()) || "RUNNING".equals(j.getStatus())) && j.getQueuedAt().isBefore(staleBefore)).count();
    return new WorkflowHealthAnalyticsResponse(tenantId, countBy(jobs, ProcessingJob::getStatus), countStatus(jobs, "FAILED"), staleJobs, auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId).size(), operatorActionRepository.findTop25ByTenantIdOrderByCreatedAtDesc(tenantId).size(), clock.instant());
  }

  @Transactional(readOnly = true)
  public Stage8CommandCenterAnalyticsResponse stage8CommandCenter() {
    UUID tenantId = TenantContext.requireTenantId();
    Stage8ChannelVolumeResponse channelVolume = stage8ChannelVolume();
    Stage8OperatorReviewAnalyticsResponse review = stage8OperatorReview();
    long draftsPrepared = draftsPrepared(tenantId);
    long totalRequests = channelVolume.totalInboundRequests();
    long exceptionCount = review.validationBackedReviewCount() + review.botOnlyHandoffCount();
    return new Stage8CommandCenterAnalyticsResponse(
        tenantId,
        totalRequests,
        review.botOnlyHandoffCount(),
        review.validationBackedReviewCount(),
        review.blockedUnsafeDraftAttempts(),
        rate(exceptionCount, totalRequests),
        rate(draftsPrepared, totalRequests),
        draftsPrepared,
        channelVolume.requestVolumeByChannel(),
        clock.instant());
  }

  @Transactional(readOnly = true)
  public Stage8ChannelVolumeResponse stage8ChannelVolume() {
    UUID tenantId = TenantContext.requireTenantId();
    Map<String, Long> volume = new LinkedHashMap<>();
    channelMessageRepository.findByTenantIdOrderByReceivedAtDesc(tenantId).stream()
        .collect(Collectors.groupingBy(ChannelMessage::getChannel, LinkedHashMap::new, Collectors.counting()))
        .forEach((channel, count) -> volume.merge(channel, count, Long::sum));
    inboundDocumentRepository.findByTenantIdOrderByReceivedAtDesc(tenantId).stream()
        .collect(Collectors.groupingBy(InboundDocument::getSourceChannel, LinkedHashMap::new, Collectors.counting()))
        .forEach((channel, count) -> volume.merge(channel, count, Long::sum));
    long total = volume.values().stream().mapToLong(Long::longValue).sum();
    return new Stage8ChannelVolumeResponse(tenantId, volume, total, clock.instant());
  }

  @Transactional(readOnly = true)
  public Stage8OperatorReviewAnalyticsResponse stage8OperatorReview() {
    UUID tenantId = TenantContext.requireTenantId();
    List<ExceptionCase> cases = exceptionCaseRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    long validationBacked = cases.stream().filter(this::validationBacked).count();
    long botOnly = cases.stream().filter(this::botOnlyHandoff).count();
    long open = cases.stream().filter(c -> !List.of("RESOLVED", "REJECTED", "CANCELLED").contains(c.getStatus())).count();
    List<ValidationIssue> openIssues = validationIssueRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, "OPEN");
    List<ValidationRun> runs = validationRunRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    List<ApprovalRequirement> approvals = runs.stream()
        .flatMap(run -> approvalRequirementRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(tenantId, run.getId()).stream())
        .toList();
    return new Stage8OperatorReviewAnalyticsResponse(
        tenantId,
        validationBacked,
        botOnly,
        open,
        blockedUnsafeDraftAttempts(tenantId, cases),
        averageReviewCycleHours(cases),
        riskCount(openIssues, approvals, "DISCOUNT"),
        riskCount(openIssues, approvals, "MARGIN"),
        clock.instant());
  }

  @Transactional(readOnly = true)
  public Stage8BotHandoffAnalyticsResponse stage8BotHandoffs() {
    UUID tenantId = TenantContext.requireTenantId();
    List<ExceptionCase> botCases = exceptionCaseRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
        .filter(this::botOnlyHandoff)
        .toList();
    return new Stage8BotHandoffAnalyticsResponse(
        tenantId,
        botCases.size(),
        botCases.stream().filter(c -> !List.of("RESOLVED", "REJECTED", "CANCELLED").contains(c.getStatus())).count(),
        blockedUnsafeDraftAttempts(tenantId, botCases),
        countBy(botCases, ExceptionCase::getStatus),
        clock.instant());
  }

  private Map<String, Long> channelBreakdown(UUID tenantId) {
    Map<String, Long> breakdown = new LinkedHashMap<>();
    breakdown.put("TELEGRAM", 0L);
    channelMessageRepository.findByTenantIdOrderByReceivedAtDesc(tenantId).stream()
        .collect(Collectors.groupingBy(ChannelMessage::getChannel, LinkedHashMap::new, Collectors.counting()))
        .forEach(breakdown::put);
    botMessageRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
        .collect(Collectors.groupingBy(BotMessage::getChannel, LinkedHashMap::new, Collectors.counting()))
        .forEach((channel, count) -> breakdown.merge(channel, count, (canonical, fallback) -> canonical == 0L ? fallback : canonical));
    return breakdown;
  }

  private List<AutomationReadinessIndicator> readiness(ValidationAnalyticsResponse validation, ReviewAnalyticsResponse review, BotAnalyticsResponse bot, WorkflowHealthAnalyticsResponse health) {
    return List.of(
        new AutomationReadinessIndicator("externalWrites", "DISABLED", "Stage 8 is read-only analytics; connector and ChangeRequest execution are out of scope."),
        new AutomationReadinessIndicator("validationBacklog", validation.needsReviewCount() == 0 && validation.blockedCount() == 0 ? "READY" : "REVIEW_REQUIRED", "Validation output must be reviewed before any later workflow."),
        new AutomationReadinessIndicator("reviewBacklog", review.openReviewBacklog() == 0 ? "READY" : "REVIEW_REQUIRED", "Open review cases require human decisions."),
        new AutomationReadinessIndicator("botHandoffs", bot.handoffCount() == 0 ? "READY" : "REVIEW_REQUIRED", "Bot handoffs require operator attention."),
        new AutomationReadinessIndicator("workflowHealth", health.failedJobs() == 0 && health.staleJobs() == 0 ? "READY" : "ATTENTION_REQUIRED", "Failed or stale jobs need operator review."));
  }

  private boolean validationBacked(ExceptionCase reviewCase) {
    return reviewCase.getValidationRunId() != null && reviewCase.getExtractionResultId() != null;
  }

  private boolean botOnlyHandoff(ExceptionCase reviewCase) {
    return "BOT_CONVERSATION".equals(reviewCase.getSourceType()) && !validationBacked(reviewCase);
  }

  private long draftsPrepared(UUID tenantId) {
    return draftQuoteRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).size()
        + draftOrderRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).size();
  }

  private long blockedUnsafeDraftAttempts(UUID tenantId, List<ExceptionCase> cases) {
    var botCaseIds = cases.stream().filter(this::botOnlyHandoff).map(c -> c.getId().toString()).collect(Collectors.toSet());
    if (botCaseIds.isEmpty()) return 0;
    return auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId).stream()
        .filter(event -> "DRAFT_PREPARATION_BLOCKED".equals(event.getAction()))
        .filter(event -> botCaseIds.contains(event.getEntityId()))
        .count();
  }

  private BigDecimal averageReviewCycleHours(List<ExceptionCase> cases) {
    List<BigDecimal> durations = cases.stream()
        .filter(c -> c.getResolvedAt() != null)
        .map(c -> BigDecimal.valueOf(Duration.between(c.getCreatedAt(), c.getResolvedAt()).toMinutes()).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP))
        .toList();
    if (durations.isEmpty()) return BigDecimal.ZERO;
    return durations.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(durations.size()), 2, RoundingMode.HALF_UP);
  }

  private long riskCount(List<ValidationIssue> issues, List<ApprovalRequirement> approvals, String marker) {
    return issues.stream().filter(issue -> issue.getIssueType().contains(marker)).count()
        + approvals.stream().filter(approval -> approval.getRequirementType().contains(marker)).count();
  }

  private BigDecimal rate(long numerator, long denominator) {
    if (denominator == 0) return BigDecimal.ZERO;
    return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
  }

  private static <T> Map<String, Long> countBy(List<T> values, Function<T, String> classifier) {
    return values.stream().collect(Collectors.groupingBy(classifier, LinkedHashMap::new, Collectors.counting()));
  }

  private static <T> Map<String, Long> topCounts(List<T> values, Function<T, String> classifier) {
    return countBy(values, classifier).entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
  }

  private static <T> long countStatus(List<T> values, String status) {
    return values.stream().filter(value -> {
      if (value instanceof ExceptionCase c) return status.equals(c.getStatus());
      if (value instanceof ProcessingJob j) return status.equals(j.getStatus());
      return false;
    }).count();
  }
}
