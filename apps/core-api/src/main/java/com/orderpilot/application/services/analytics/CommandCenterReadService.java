package com.orderpilot.application.services.analytics;

import com.orderpilot.api.dto.CommandCenterDtos.AuditTimelineItemDto;
import com.orderpilot.api.dto.CommandCenterDtos.AuditTimelinePreviewDto;
import com.orderpilot.api.dto.CommandCenterDtos.CommandCenterMetricDto;
import com.orderpilot.api.dto.CommandCenterDtos.CommandCenterSummaryDto;
import com.orderpilot.api.dto.CommandCenterDtos.OutboxHealthDto;
import com.orderpilot.api.dto.CommandCenterDtos.ReconciliationPreviewCaseDto;
import com.orderpilot.api.dto.CommandCenterDtos.ReconciliationPreviewDto;
import com.orderpilot.api.dto.CommandCenterDtos.RuntimeHealthDto;
import com.orderpilot.api.dto.CommandCenterDtos.WorkQueueItemDto;
import com.orderpilot.api.dto.CommandCenterDtos.WorkQueuePreviewDto;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.integration.OutboxEvent;
import com.orderpilot.domain.integration.OutboxEventRepository;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.domain.reconciliation.ReconciliationCase;
import com.orderpilot.domain.reconciliation.ReconciliationCaseRepository;
import com.orderpilot.domain.reconciliation.ReconciliationSeverity;
import com.orderpilot.domain.reconciliation.ReconciliationStatus;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.domain.workspace.ExceptionCase;
import com.orderpilot.domain.workspace.ExceptionCaseRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-21 — read-only aggregation for the Operant Transaction Command Center.
 *
 * <p>Every read is tenant-scoped and bounded: count queries, {@code findTop20...} preview windows,
 * and {@code Page} requests with an explicit small page size. No full-table scans, no unbounded
 * joins, and no mutation. Metrics that have no real backing data source yet are returned as
 * {@code available=false} rather than fabricated.
 */
@Service
public class CommandCenterReadService {
  // Open operator-review states (mirrors CommerceAnalyticsService.review() backlog definition).
  private static final List<String> OPEN_CASE_STATUSES =
      List.of("REVIEW_REQUIRED", "IN_REVIEW", "OPEN", "WAITING_APPROVAL");
  private static final List<String> HIGH_RISK_SEVERITIES = List.of("HIGH", "CRITICAL");
  private static final int WORK_QUEUE_LIMIT = 20;
  private static final int AUDIT_LIMIT = 20;
  private static final int RECONCILIATION_LIMIT = 10;
  private static final String WORK_QUEUE_LINK_ROUTE = "/exception-cockpit";

  private final ExceptionCaseRepository exceptionCaseRepository;
  private final DraftQuoteRepository draftQuoteRepository;
  private final DraftOrderRepository draftOrderRepository;
  private final ProcessingJobRepository processingJobRepository;
  private final OutboxEventRepository outboxEventRepository;
  private final AuditEventRepository auditEventRepository;
  private final ReconciliationCaseRepository reconciliationCaseRepository;
  private final Clock clock;

  public CommandCenterReadService(
      ExceptionCaseRepository exceptionCaseRepository,
      DraftQuoteRepository draftQuoteRepository,
      DraftOrderRepository draftOrderRepository,
      ProcessingJobRepository processingJobRepository,
      OutboxEventRepository outboxEventRepository,
      AuditEventRepository auditEventRepository,
      ReconciliationCaseRepository reconciliationCaseRepository,
      Clock clock) {
    this.exceptionCaseRepository = exceptionCaseRepository;
    this.draftQuoteRepository = draftQuoteRepository;
    this.draftOrderRepository = draftOrderRepository;
    this.processingJobRepository = processingJobRepository;
    this.outboxEventRepository = outboxEventRepository;
    this.auditEventRepository = auditEventRepository;
    this.reconciliationCaseRepository = reconciliationCaseRepository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public CommandCenterSummaryDto summary() {
    UUID tenantId = TenantContext.requireTenantId();
    Instant generatedAt = clock.instant();
    return new CommandCenterSummaryDto(
        tenantId,
        metrics(tenantId),
        workQueue(tenantId, generatedAt),
        runtimeHealth(tenantId),
        outboxHealth(tenantId),
        auditTimeline(tenantId, generatedAt),
        reconciliation(tenantId, generatedAt),
        generatedAt);
  }

  private List<CommandCenterMetricDto> metrics(UUID tenantId) {
    long pendingReviews = exceptionCaseRepository.countByTenantIdAndStatusIn(tenantId, OPEN_CASE_STATUSES);
    long highRisk = exceptionCaseRepository.countByTenantIdAndSeverityIn(tenantId, HIGH_RISK_SEVERITIES);
    long draftQuotes = draftQuoteRepository.countByTenantId(tenantId);
    long draftOrders = draftOrderRepository.countByTenantId(tenantId);
    long outboxPending = outboxEventRepository.countByTenantIdAndStatus(tenantId, "PENDING");
    long jobsFailed = processingJobRepository.countByTenantIdAndStatus(tenantId, "FAILED");
    return List.of(
        CommandCenterMetricDto.count("pendingReviews", "Pending reviews", pendingReviews),
        CommandCenterMetricDto.count("highRiskCases", "High-risk / critical cases", highRisk),
        CommandCenterMetricDto.count("draftQuotes", "Draft quotes", draftQuotes),
        CommandCenterMetricDto.count("draftOrders", "Draft orders", draftOrders),
        CommandCenterMetricDto.count("outboxPending", "Outbox pending", outboxPending),
        CommandCenterMetricDto.count("jobsFailed", "Failed processing jobs", jobsFailed),
        CommandCenterMetricDto.unavailable(
            "automationReadiness",
            "Automation readiness",
            "No production readiness model is wired yet; shown as unavailable rather than a fabricated percentage."));
  }

  private WorkQueuePreviewDto workQueue(UUID tenantId, Instant generatedAt) {
    long openTotal = exceptionCaseRepository.countByTenantIdAndStatusIn(tenantId, OPEN_CASE_STATUSES);
    List<WorkQueueItemDto> items =
        exceptionCaseRepository
            .findTop20ByTenantIdAndStatusInOrderByCreatedAtDesc(tenantId, OPEN_CASE_STATUSES)
            .stream()
            .map(this::toWorkQueueItem)
            .toList();
    boolean partial = openTotal > items.size();
    return new WorkQueuePreviewDto(items, openTotal, WORK_QUEUE_LIMIT, partial, generatedAt);
  }

  private WorkQueueItemDto toWorkQueueItem(ExceptionCase c) {
    return new WorkQueueItemDto(
        c.getId(),
        c.getCaseNumber(),
        c.getTitle(),
        c.getStatus(),
        c.getSeverity(),
        c.getPriority(),
        c.getSourceType(),
        c.getCreatedAt(),
        WORK_QUEUE_LINK_ROUTE);
  }

  private RuntimeHealthDto runtimeHealth(UUID tenantId) {
    long pending = processingJobRepository.countByTenantIdAndStatus(tenantId, "PENDING");
    long running = processingJobRepository.countByTenantIdAndStatus(tenantId, "RUNNING");
    long failed = processingJobRepository.countByTenantIdAndStatus(tenantId, "FAILED");
    Instant lastQueuedAt =
        processingJobRepository
            .findFirstByTenantIdOrderByQueuedAtDesc(tenantId)
            .map(ProcessingJob::getQueuedAt)
            .orElse(null);
    boolean available = lastQueuedAt != null;
    String note =
        available ? null : "No processing jobs recorded for this tenant yet.";
    return new RuntimeHealthDto(available, pending, running, failed, lastQueuedAt, failed > 0, note);
  }

  private OutboxHealthDto outboxHealth(UUID tenantId) {
    long pending = outboxEventRepository.countByTenantIdAndStatus(tenantId, "PENDING");
    long published = outboxEventRepository.countByTenantIdAndStatus(tenantId, "PUBLISHED_INTERNAL_ONLY");
    long skipped = outboxEventRepository.countByTenantIdAndStatus(tenantId, "SKIPPED_EXTERNAL_DISABLED");
    Instant lastPublishedAt =
        outboxEventRepository
            .findFirstByTenantIdAndPublishedAtIsNotNullOrderByPublishedAtDesc(tenantId)
            .map(OutboxEvent::getPublishedAt)
            .orElse(null);
    boolean available = (pending + published + skipped) > 0;
    // The outbox model tracks no FAILED status; an unprocessed pending backlog is the attention signal.
    boolean degraded = pending > 0;
    String note =
        available
            ? "External publication remains disabled; PUBLISHED_INTERNAL_ONLY / SKIPPED_EXTERNAL_DISABLED are internal-only states."
            : "No outbox events recorded for this tenant yet.";
    return new OutboxHealthDto(available, pending, published, skipped, lastPublishedAt, degraded, note);
  }

  private AuditTimelinePreviewDto auditTimeline(UUID tenantId, Instant generatedAt) {
    List<AuditTimelineItemDto> items =
        auditEventRepository.findTop20ByTenantIdOrderByOccurredAtDesc(tenantId).stream()
            .map(this::toAuditItem)
            .toList();
    // If the bounded window is full there may be more rows beyond the preview.
    boolean partial = items.size() >= AUDIT_LIMIT;
    return new AuditTimelinePreviewDto(items, AUDIT_LIMIT, partial, generatedAt);
  }

  private AuditTimelineItemDto toAuditItem(AuditEvent e) {
    // Identifiers + action only. The audit metadata JSON blob is intentionally excluded.
    return new AuditTimelineItemDto(
        e.getActorId(), e.getAction(), e.getEntityType(), e.getEntityId(), e.getOccurredAt());
  }

  private ReconciliationPreviewDto reconciliation(UUID tenantId, Instant generatedAt) {
    long openCases =
        reconciliationCaseRepository.countByTenantIdAndStatus(tenantId, ReconciliationStatus.OPEN);
    long highSeverityOpen =
        reconciliationCaseRepository.countByTenantIdAndSeverityAndStatus(
            tenantId, ReconciliationSeverity.HIGH, ReconciliationStatus.OPEN);
    List<ReconciliationPreviewCaseDto> recentCases =
        reconciliationCaseRepository
            .findByTenantIdOrderByUpdatedAtDesc(tenantId, PageRequest.of(0, RECONCILIATION_LIMIT))
            .map(this::toReconciliationCase)
            .getContent();
    boolean partial = openCases > recentCases.size();
    return new ReconciliationPreviewDto(
        true,
        openCases,
        highSeverityOpen,
        recentCases,
        RECONCILIATION_LIMIT,
        partial,
        "Derived from the inventory reconciliation domain. No payment-provider reconciliation is implemented.",
        generatedAt);
  }

  private ReconciliationPreviewCaseDto toReconciliationCase(ReconciliationCase c) {
    return new ReconciliationPreviewCaseDto(
        c.getId(),
        c.getProductId(),
        c.getLocationId(),
        c.getMismatchQuantity(),
        c.getSeverity().name(),
        c.getStatus().name(),
        c.getUpdatedAt());
  }
}
