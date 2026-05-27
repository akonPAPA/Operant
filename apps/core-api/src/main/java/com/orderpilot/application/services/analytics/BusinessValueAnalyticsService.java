package com.orderpilot.application.services.analytics;

import com.orderpilot.api.dto.Stage8Dtos.*;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.intake.InboundDocumentRepository;
import com.orderpilot.domain.reconciliation.ReconciliationCase;
import com.orderpilot.domain.reconciliation.ReconciliationCaseRepository;
import com.orderpilot.domain.validation.DiscountCheckResult;
import com.orderpilot.domain.validation.DiscountCheckResultRepository;
import com.orderpilot.domain.validation.MarginCheckResult;
import com.orderpilot.domain.validation.MarginCheckResultRepository;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessValueAnalyticsService {
  private final RoiAssumptionsService assumptionsService;
  private final CommerceAnalyticsService commerceAnalyticsService;
  private final ChannelMessageRepository channelMessageRepository;
  private final InboundDocumentRepository inboundDocumentRepository;
  private final ExceptionCaseRepository exceptionCaseRepository;
  private final AuditEventRepository auditEventRepository;
  private final DraftQuoteRepository draftQuoteRepository;
  private final DraftQuoteLineRepository draftQuoteLineRepository;
  private final DraftOrderRepository draftOrderRepository;
  private final DiscountCheckResultRepository discountCheckResultRepository;
  private final MarginCheckResultRepository marginCheckResultRepository;
  private final ReconciliationCaseRepository reconciliationCaseRepository;
  private final Clock clock;

  public BusinessValueAnalyticsService(RoiAssumptionsService assumptionsService, CommerceAnalyticsService commerceAnalyticsService, ChannelMessageRepository channelMessageRepository, InboundDocumentRepository inboundDocumentRepository, ExceptionCaseRepository exceptionCaseRepository, AuditEventRepository auditEventRepository, DraftQuoteRepository draftQuoteRepository, DraftQuoteLineRepository draftQuoteLineRepository, DraftOrderRepository draftOrderRepository, DiscountCheckResultRepository discountCheckResultRepository, MarginCheckResultRepository marginCheckResultRepository, ReconciliationCaseRepository reconciliationCaseRepository, Clock clock) {
    this.assumptionsService = assumptionsService;
    this.commerceAnalyticsService = commerceAnalyticsService;
    this.channelMessageRepository = channelMessageRepository;
    this.inboundDocumentRepository = inboundDocumentRepository;
    this.exceptionCaseRepository = exceptionCaseRepository;
    this.auditEventRepository = auditEventRepository;
    this.draftQuoteRepository = draftQuoteRepository;
    this.draftQuoteLineRepository = draftQuoteLineRepository;
    this.draftOrderRepository = draftOrderRepository;
    this.discountCheckResultRepository = discountCheckResultRepository;
    this.marginCheckResultRepository = marginCheckResultRepository;
    this.reconciliationCaseRepository = reconciliationCaseRepository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Stage8ValueSummaryResponse summary() {
    UUID tenantId = TenantContext.requireTenantId();
    RoiAssumptionsResponse assumptions = assumptionsService.currentForTenant(tenantId);
    Metrics metrics = metrics(tenantId, assumptions);
    return new Stage8ValueSummaryResponse(
        tenantId,
        metrics.estimatedHoursSaved(),
        metrics.estimatedLaborCostSaved(),
        metrics.averageReviewCycleHours(),
        metrics.averageDraftPreparationCycleHours(),
        metrics.blockedUnsafeAttempts(),
        metrics.discountLeakageCount(),
        metrics.estimatedDiscountLeakageValue(),
        metrics.marginRiskCount(),
        metrics.estimatedMarginRiskImpact(),
        metrics.substituteRecoveredRevenue(),
        metrics.inventoryDiscrepancyValue(),
        metrics.staleInventoryRiskCount(),
        assumptions.defaultCurrency(),
        true,
        assumptions.defaultAssumptions(),
        clock.instant());
  }

  @Transactional(readOnly = true)
  public Stage8ValueLeakageResponse leakage() {
    UUID tenantId = TenantContext.requireTenantId();
    RoiAssumptionsResponse assumptions = assumptionsService.currentForTenant(tenantId);
    Metrics metrics = metrics(tenantId, assumptions);
    return new Stage8ValueLeakageResponse(
        tenantId,
        metrics.discountLeakageCount(),
        metrics.estimatedDiscountLeakageValue(),
        metrics.marginRiskCount(),
        metrics.estimatedMarginRiskImpact(),
        metrics.inventoryDiscrepancyValue(),
        metrics.staleInventoryRiskCount(),
        metrics.exceptionCausesBreakdown(),
        metrics.topReconciliationIssues(),
        assumptions.defaultCurrency(),
        true,
        clock.instant());
  }

  @Transactional(readOnly = true)
  public Stage8ValueProductivityResponse productivity() {
    UUID tenantId = TenantContext.requireTenantId();
    RoiAssumptionsResponse assumptions = assumptionsService.currentForTenant(tenantId);
    Metrics metrics = metrics(tenantId, assumptions);
    Stage8CommandCenterAnalyticsResponse commandCenter = commerceAnalyticsService.stage8CommandCenter();
    return new Stage8ValueProductivityResponse(
        tenantId,
        metrics.totalInboundRequests(),
        commandCenter.automationRate(),
        commandCenter.exceptionRate(),
        metrics.estimatedHoursSaved(),
        metrics.estimatedLaborCostSaved(),
        metrics.averageReviewCycleHours(),
        metrics.averageDraftPreparationCycleHours(),
        metrics.draftQuoteCount(),
        metrics.draftOrderCount(),
        metrics.blockedUnsafeAttempts(),
        assumptions.defaultCurrency(),
        true,
        clock.instant());
  }

  @Transactional(readOnly = true)
  public Stage8PilotRoiReportResponse export() {
    UUID tenantId = TenantContext.requireTenantId();
    RoiAssumptionsResponse assumptions = assumptionsService.currentForTenant(tenantId);
    Metrics metrics = metrics(tenantId, assumptions);
    Stage8CommandCenterAnalyticsResponse commandCenter = commerceAnalyticsService.stage8CommandCenter();
    Instant now = clock.instant();
    return new Stage8PilotRoiReportResponse(
        tenantId,
        null,
        now,
        metrics.totalInboundRequests(),
        commandCenter.automationRate(),
        commandCenter.exceptionRate(),
        commandCenter.botOnlyHandoffCount(),
        metrics.draftQuoteCount(),
        metrics.draftOrderCount(),
        metrics.blockedUnsafeAttempts(),
        metrics.estimatedHoursSaved(),
        metrics.estimatedLaborCostSaved(),
        metrics.marginRiskCount(),
        metrics.discountLeakageCount(),
        metrics.inventoryDiscrepancyValue(),
        metrics.exceptionCausesBreakdown(),
        metrics.topReconciliationIssues(),
        assumptions,
        true,
        now);
  }

  private Metrics metrics(UUID tenantId, RoiAssumptionsResponse assumptions) {
    List<ExceptionCase> cases = exceptionCaseRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    List<DraftQuote> quotes = draftQuoteRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    List<DraftOrder> orders = draftOrderRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    List<DiscountCheckResult> discounts = discountCheckResultRepository.findByTenantId(tenantId);
    List<MarginCheckResult> margins = marginCheckResultRepository.findByTenantId(tenantId);
    List<ReconciliationCase> reconciliationCases = reconciliationCaseRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId, PageRequest.of(0, 100)).getContent();
    long totalRequests = channelMessageRepository.findByTenantIdOrderByReceivedAtDesc(tenantId).size()
        + inboundDocumentRepository.findByTenantIdOrderByReceivedAtDesc(tenantId).size();
    long blockedUnsafe = blockedUnsafeDraftAttempts(tenantId);
    BigDecimal hoursSaved = BigDecimal.valueOf(totalRequests)
        .multiply(assumptions.averageManualHandlingMinutesPerRequest())
        .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    BigDecimal laborCostSaved = hoursSaved.multiply(assumptions.averageFullyLoadedOperatorHourlyCost()).setScale(2, RoundingMode.HALF_UP);
    long discountLeakageCount = discounts.stream().filter(DiscountCheckResult::isRequiresApproval).count();
    BigDecimal discountLeakageValue = sum(quotes.stream().map(DraftQuote::getDiscountAmount).toList()).add(sum(orders.stream().map(DraftOrder::getDiscountAmount).toList()));
    long marginRiskCount = margins.stream().filter(MarginCheckResult::isRequiresApproval).count();
    BigDecimal marginImpact = margins.stream()
        .filter(MarginCheckResult::isRequiresApproval)
        .map(this::marginImpact)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
    BigDecimal substituteRecoveredRevenue = quotes.stream()
        .flatMap(quote -> draftQuoteLineRepository.findByTenantIdAndDraftQuoteId(tenantId, quote.getId()).stream())
        .filter(line -> line.getSelectedSubstituteProductId() != null)
        .map(DraftQuoteLine::getLineTotal)
        .filter(value -> value != null)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
    BigDecimal inventoryDiscrepancyValue = BigDecimal.ZERO;
    long staleRiskCount = reconciliationCases.stream().filter(c -> c.getLikelyCauses().contains("STALE_INVENTORY_SNAPSHOT")).count();
    return new Metrics(
        totalRequests,
        hoursSaved,
        laborCostSaved,
        averageReviewCycleHours(cases),
        averageDraftPreparationCycleHours(cases, quotes, orders),
        blockedUnsafe,
        discountLeakageCount,
        discountLeakageValue,
        marginRiskCount,
        marginImpact,
        substituteRecoveredRevenue,
        inventoryDiscrepancyValue,
        staleRiskCount,
        quotes.size(),
        orders.size(),
        topCounts(cases, this::exceptionCategory),
        topCounts(reconciliationCases, this::reconciliationCategory));
  }

  private long blockedUnsafeDraftAttempts(UUID tenantId) {
    return auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantId).stream()
        .filter(event -> "DRAFT_PREPARATION_BLOCKED".equals(event.getAction()))
        .count();
  }

  private BigDecimal marginImpact(MarginCheckResult result) {
    if (result.getUnitCost() == null || result.getUnitPrice() == null) return BigDecimal.ZERO;
    BigDecimal gap = result.getUnitCost().subtract(result.getUnitPrice());
    return gap.compareTo(BigDecimal.ZERO) > 0 ? gap : BigDecimal.ZERO;
  }

  private BigDecimal averageReviewCycleHours(List<ExceptionCase> cases) {
    return averageHours(cases.stream()
        .filter(c -> c.getResolvedAt() != null)
        .map(c -> Duration.between(c.getCreatedAt(), c.getResolvedAt()))
        .toList());
  }

  private BigDecimal averageDraftPreparationCycleHours(List<ExceptionCase> cases, List<DraftQuote> quotes, List<DraftOrder> orders) {
    Map<UUID, Instant> caseCreatedAt = cases.stream().collect(Collectors.toMap(ExceptionCase::getId, ExceptionCase::getCreatedAt, (a, b) -> a));
    List<Duration> durations = new java.util.ArrayList<>();
    quotes.stream().filter(q -> q.getSourceExceptionCaseId() != null && caseCreatedAt.containsKey(q.getSourceExceptionCaseId()))
        .map(q -> Duration.between(caseCreatedAt.get(q.getSourceExceptionCaseId()), q.getCreatedAt()))
        .forEach(durations::add);
    orders.stream().filter(o -> o.getSourceExceptionCaseId() != null && caseCreatedAt.containsKey(o.getSourceExceptionCaseId()))
        .map(o -> Duration.between(caseCreatedAt.get(o.getSourceExceptionCaseId()), o.getCreatedAt()))
        .forEach(durations::add);
    return averageHours(durations);
  }

  private BigDecimal averageHours(List<Duration> durations) {
    if (durations.isEmpty()) return BigDecimal.ZERO;
    BigDecimal minutes = durations.stream()
        .map(duration -> BigDecimal.valueOf(duration.toMinutes()))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    return minutes.divide(BigDecimal.valueOf(durations.size()), 2, RoundingMode.HALF_UP)
        .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
  }

  private String exceptionCategory(ExceptionCase reviewCase) {
    if ("BOT_CONVERSATION".equals(reviewCase.getSourceType())) return "BOT_HANDOFF";
    if (reviewCase.getValidationRunId() != null) return "VALIDATION_REVIEW";
    return reviewCase.getSourceType();
  }

  private String reconciliationCategory(ReconciliationCase reconciliationCase) {
    String causes = reconciliationCase.getLikelyCauses();
    if (causes == null || causes.isBlank() || "[]".equals(causes)) return "UNKNOWN";
    return causes.replace("[", "").replace("]", "").replace("\"", "").split(",")[0].trim();
  }

  private BigDecimal sum(List<BigDecimal> values) {
    return values.stream().filter(value -> value != null).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
  }

  private <T> Map<String, Long> topCounts(List<T> values, Function<T, String> classifier) {
    return values.stream()
        .collect(Collectors.groupingBy(classifier, LinkedHashMap::new, Collectors.counting()))
        .entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
  }

  private record Metrics(
      long totalInboundRequests,
      BigDecimal estimatedHoursSaved,
      BigDecimal estimatedLaborCostSaved,
      BigDecimal averageReviewCycleHours,
      BigDecimal averageDraftPreparationCycleHours,
      long blockedUnsafeAttempts,
      long discountLeakageCount,
      BigDecimal estimatedDiscountLeakageValue,
      long marginRiskCount,
      BigDecimal estimatedMarginRiskImpact,
      BigDecimal substituteRecoveredRevenue,
      BigDecimal inventoryDiscrepancyValue,
      long staleInventoryRiskCount,
      long draftQuoteCount,
      long draftOrderCount,
      Map<String, Long> exceptionCausesBreakdown,
      Map<String, Long> topReconciliationIssues
  ) {}
}
