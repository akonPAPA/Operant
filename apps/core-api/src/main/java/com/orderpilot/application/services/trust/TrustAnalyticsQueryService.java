package com.orderpilot.application.services.trust;

import com.orderpilot.api.dto.TrustAnalyticsDtos.CounterpartyTrustDashboardDto;
import com.orderpilot.api.dto.TrustAnalyticsDtos.DocumentAnomalyTrendDto;
import com.orderpilot.api.dto.TrustAnalyticsDtos.OutstandingDebtItemDto;
import com.orderpilot.api.dto.TrustAnalyticsDtos.TrustRiskDistributionDto;
import com.orderpilot.api.dto.TrustAnalyticsDtos.TrustReviewQueueItemDto;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.payment.PaymentObligationStatus;
import com.orderpilot.domain.trust.TrustRiskLevel;
import com.orderpilot.domain.trust.TrustSignalCode;
import com.orderpilot.domain.trust.TrustSignalSeverity;
import com.orderpilot.domain.trust.analytics.CounterpartyTrustDashboardView;
import com.orderpilot.domain.trust.analytics.CounterpartyTrustDashboardViewRepository;
import com.orderpilot.domain.trust.analytics.DocumentAnomalyTrendView;
import com.orderpilot.domain.trust.analytics.DocumentAnomalyTrendViewRepository;
import com.orderpilot.domain.trust.analytics.OutstandingDebtView;
import com.orderpilot.domain.trust.analytics.OutstandingDebtViewRepository;
import com.orderpilot.domain.trust.analytics.TrustReviewQueueView;
import com.orderpilot.domain.trust.analytics.TrustReviewQueueViewRepository;
import com.orderpilot.domain.trust.analytics.TrustRiskDistributionView;
import com.orderpilot.domain.trust.analytics.TrustRiskDistributionViewRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-17E Trust Analytics Read Models — read/query side.
 *
 * Serves bounded, tenant-scoped analytics DTOs straight from the derived read models — never a heavy join
 * over the operational tables on every request. Tenant is resolved from {@link TenantContext}; path/query
 * ids are never trusted across tenants (every finder is tenant-isolated). Lists are always paginated and
 * limit-clamped. No raw document/OCR/prompt text, bank credentials, account numbers, or secrets are ever
 * returned.
 */
@Service
public class TrustAnalyticsQueryService {
  public static final int DEFAULT_LIMIT = 25;
  static final int MAX_LIMIT = 100;
  static final int MAX_PERIOD_ROWS = 366;

  private final TrustReviewQueueViewRepository reviewQueueViews;
  private final CounterpartyTrustDashboardViewRepository dashboardViews;
  private final OutstandingDebtViewRepository outstandingDebtViews;
  private final DocumentAnomalyTrendViewRepository anomalyTrendViews;
  private final TrustRiskDistributionViewRepository distributionViews;
  private Clock clock;

  public TrustAnalyticsQueryService(
      TrustReviewQueueViewRepository reviewQueueViews,
      CounterpartyTrustDashboardViewRepository dashboardViews,
      OutstandingDebtViewRepository outstandingDebtViews,
      DocumentAnomalyTrendViewRepository anomalyTrendViews,
      TrustRiskDistributionViewRepository distributionViews,
      Clock clock) {
    this.reviewQueueViews = reviewQueueViews;
    this.dashboardViews = dashboardViews;
    this.outstandingDebtViews = outstandingDebtViews;
    this.anomalyTrendViews = anomalyTrendViews;
    this.distributionViews = distributionViews;
    this.clock = clock;
  }

  // ----------------------------- review queue -----------------------------

  @Transactional(readOnly = true)
  public List<TrustReviewQueueItemDto> listReviewQueue(
      String riskLevel, String approvalStatus, Boolean blocking, int page, int size) {
    UUID tenantId = TenantContext.requireTenantId();
    Pageable pageable = PageRequest.of(Math.max(page, 0), clampLimit(size));
    TrustRiskLevel level = parseEnum(TrustRiskLevel.class, riskLevel);
    String approval = blankToNull(approvalStatus);

    List<TrustReviewQueueView> rows;
    if (level != null && blocking != null) {
      rows = reviewQueueViews.findByTenantIdAndRiskLevelAndBlockingOrderByCreatedAtDesc(
          tenantId, level, blocking, pageable);
    } else if (level != null) {
      rows = reviewQueueViews.findByTenantIdAndRiskLevelOrderByCreatedAtDesc(tenantId, level, pageable);
    } else if (approval != null) {
      rows = reviewQueueViews.findByTenantIdAndApprovalStatusOrderByCreatedAtDesc(
          tenantId, approval.toUpperCase(Locale.ROOT), pageable);
    } else if (blocking != null) {
      rows = reviewQueueViews.findByTenantIdAndBlockingOrderByCreatedAtDesc(tenantId, blocking, pageable);
    } else {
      rows = reviewQueueViews.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
    }
    return rows.stream().map(this::toDto).toList();
  }

  // ----------------------------- counterparty dashboard -----------------------------

  @Transactional(readOnly = true)
  public CounterpartyTrustDashboardDto getCounterpartyDashboard(UUID counterpartyId) {
    UUID tenantId = TenantContext.requireTenantId();
    CounterpartyTrustDashboardView row = dashboardViews
        .findByTenantIdAndCounterpartyId(tenantId, counterpartyId)
        .orElseThrow(() -> new NotFoundException("Counterparty trust dashboard not found"));
    return toDto(row);
  }

  // ----------------------------- outstanding debt -----------------------------

  @Transactional(readOnly = true)
  public List<OutstandingDebtItemDto> listOutstandingDebt(
      String status, String riskLevel, UUID counterpartyId, int page, int size) {
    UUID tenantId = TenantContext.requireTenantId();
    Pageable pageable = PageRequest.of(Math.max(page, 0), clampLimit(size));
    PaymentObligationStatus st = parseEnum(PaymentObligationStatus.class, status);
    TrustRiskLevel level = parseEnum(TrustRiskLevel.class, riskLevel);

    List<OutstandingDebtView> rows;
    if (st != null && level != null) {
      rows = outstandingDebtViews.findByTenantIdAndStatusAndRiskLevelOrderByAmountRemainingDesc(
          tenantId, st, level, pageable);
    } else if (st != null) {
      rows = outstandingDebtViews.findByTenantIdAndStatusOrderByAmountRemainingDesc(tenantId, st, pageable);
    } else if (level != null) {
      rows = outstandingDebtViews.findByTenantIdAndRiskLevelOrderByAmountRemainingDesc(tenantId, level, pageable);
    } else if (counterpartyId != null) {
      rows = outstandingDebtViews.findByTenantIdAndCounterpartyIdOrderByAmountRemainingDesc(
          tenantId, counterpartyId, pageable);
    } else {
      rows = outstandingDebtViews.findByTenantIdOrderByAmountRemainingDesc(tenantId, pageable);
    }
    return rows.stream().map(this::toDto).toList();
  }

  // ----------------------------- document anomaly trends -----------------------------

  @Transactional(readOnly = true)
  public List<DocumentAnomalyTrendDto> listDocumentAnomalies(
      String fromPeriod, String toPeriod, String signalCode, String severity, int limit) {
    UUID tenantId = TenantContext.requireTenantId();
    String today = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
    String from = blankToNull(fromPeriod) == null ? today : fromPeriod.trim();
    String to = blankToNull(toPeriod) == null ? today : toPeriod.trim();
    Pageable pageable = PageRequest.of(0, clampPeriodRows(limit));
    TrustSignalCode code = parseEnum(TrustSignalCode.class, signalCode);
    TrustSignalSeverity sev = parseEnum(TrustSignalSeverity.class, severity);

    List<DocumentAnomalyTrendView> rows;
    if (code != null) {
      rows = anomalyTrendViews.findByTenantIdAndSignalCodeAndPeriodKeyBetweenOrderByPeriodKeyAsc(
          tenantId, code, from, to, pageable);
    } else if (sev != null) {
      rows = anomalyTrendViews.findByTenantIdAndSeverityAndPeriodKeyBetweenOrderByPeriodKeyAsc(
          tenantId, sev, from, to, pageable);
    } else {
      rows = anomalyTrendViews.findByTenantIdAndPeriodKeyBetweenOrderByPeriodKeyAscSignalCodeAsc(
          tenantId, from, to, pageable);
    }
    return rows.stream().map(this::toDto).toList();
  }

  // ----------------------------- risk distribution -----------------------------

  @Transactional(readOnly = true)
  public List<TrustRiskDistributionDto> listRiskDistribution(
      String periodKey, String fromPeriod, String toPeriod, int limit) {
    UUID tenantId = TenantContext.requireTenantId();
    String single = blankToNull(periodKey);
    if (single != null) {
      return distributionViews.findByTenantIdAndPeriodKey(tenantId, single.trim())
          .map(this::toDto).map(List::of).orElseGet(List::of);
    }
    String today = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
    String from = blankToNull(fromPeriod) == null ? today : fromPeriod.trim();
    String to = blankToNull(toPeriod) == null ? today : toPeriod.trim();
    Pageable pageable = PageRequest.of(0, clampPeriodRows(limit));
    return distributionViews
        .findByTenantIdAndPeriodKeyBetweenOrderByPeriodKeyAsc(tenantId, from, to, pageable)
        .stream().map(this::toDto).toList();
  }

  // ----------------------------- mappers -----------------------------

  private TrustReviewQueueItemDto toDto(TrustReviewQueueView v) {
    return new TrustReviewQueueItemDto(
        v.getId(), v.getTrustRiskDecisionId(), v.getSubjectType(), v.getSubjectId(),
        v.getCounterpartyId(), v.getDocumentTrustRunId(), v.getPaymentObligationId(),
        v.getRiskLevel().name(), v.getRiskScore(), v.getAction().name(), v.isBlocking(),
        v.isHumanReviewRequired(), v.getApprovalStatus(), v.getTopReasonCode(), v.getReasonSummary(),
        v.getCreatedAt(), v.getUpdatedAt(), v.getLastProjectedAt());
  }

  private CounterpartyTrustDashboardDto toDto(CounterpartyTrustDashboardView v) {
    return new CounterpartyTrustDashboardDto(
        v.getCounterpartyId(), v.getTrustScore(), v.getTrustTier().name(), v.getOrderCount(),
        v.getCompletedOrderCount(), v.getPaidOnTimeCount(), v.getOverdueCount(), v.getDisputedCount(),
        v.getHighRiskDocumentCount(), v.getCriticalRiskDocumentCount(), v.getHighRiskDecisionCount(),
        v.getCriticalRiskDecisionCount(), v.getOpenPaymentObligationCount(),
        v.getOverduePaymentObligationCount(), v.getOutstandingAmount(), v.getPrimaryCurrency(),
        v.getLastOrderAt(), v.getLastPaymentAt(), v.getLastHighRiskAt(), v.getLastCriticalRiskAt(),
        v.getUpdatedAt(), v.getLastProjectedAt());
  }

  private OutstandingDebtItemDto toDto(OutstandingDebtView v) {
    return new OutstandingDebtItemDto(
        v.getId(), v.getPaymentObligationId(), v.getCounterpartyId(), v.getOrderId(),
        v.getInvoiceMirrorId(), v.getExternalReference(), v.getAmountTotal(), v.getAmountPaid(),
        v.getAmountRemaining(), v.getCurrency(), v.getDueDate(), v.getStatus().name(),
        v.getRiskLevel().name(), v.getDaysOverdue(), v.getLinkedRiskDecisionId(), v.getTopReasonCode(),
        v.getCreatedAt(), v.getUpdatedAt(), v.getLastProjectedAt());
  }

  private DocumentAnomalyTrendDto toDto(DocumentAnomalyTrendView v) {
    return new DocumentAnomalyTrendDto(
        v.getPeriodKey(), v.getPeriodStart(), v.getPeriodEnd(), v.getSignalCode().name(),
        v.getSeverity().name(), v.getRiskLevel() == null ? null : v.getRiskLevel().name(),
        v.getCounterpartyId(), v.getCount(), v.getHighCount(), v.getCriticalCount(),
        v.getLatestSeenAt(), v.getLastProjectedAt());
  }

  private TrustRiskDistributionDto toDto(TrustRiskDistributionView v) {
    return new TrustRiskDistributionDto(
        v.getPeriodKey(), v.getPeriodStart(), v.getPeriodEnd(), v.getLowCount(), v.getMediumCount(),
        v.getHighCount(), v.getCriticalCount(), v.getApprovalRequiredCount(), v.getBlockingCount(),
        v.getOverrideCount(), v.getAvgRiskScore(), v.getLastProjectedAt());
  }

  // ----------------------------- helpers -----------------------------

  static int clampLimit(int requested) {
    if (requested <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(requested, MAX_LIMIT);
  }

  static int clampPeriodRows(int requested) {
    if (requested <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(requested, MAX_PERIOD_ROWS);
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
    String v = blankToNull(value);
    if (v == null) {
      return null;
    }
    try {
      return Enum.valueOf(type, v.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown " + type.getSimpleName() + ": " + value);
    }
  }
}
