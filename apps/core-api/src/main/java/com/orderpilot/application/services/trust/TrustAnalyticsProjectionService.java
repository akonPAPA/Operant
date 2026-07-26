package com.orderpilot.application.services.trust;

import com.orderpilot.api.dto.TrustAnalyticsDtos.TrustAnalyticsRebuildResponseDto;
import com.orderpilot.domain.payment.PaymentObligation;
import com.orderpilot.domain.payment.PaymentObligationRepository;
import com.orderpilot.domain.payment.PaymentObligationSourceType;
import com.orderpilot.domain.payment.PaymentObligationStatus;
import com.orderpilot.domain.payment.PaymentObligationStatusAggregate;
import com.orderpilot.domain.trust.CounterpartyTrustProfile;
import com.orderpilot.domain.trust.CounterpartyTrustProfileRepository;
import com.orderpilot.domain.trust.TrustApprovalRequirement;
import com.orderpilot.domain.trust.TrustApprovalRequirementRepository;
import com.orderpilot.domain.trust.TrustRiskDecision;
import com.orderpilot.domain.trust.TrustRiskDecisionRepository;
import com.orderpilot.domain.trust.TrustRiskDecisionStatus;
import com.orderpilot.domain.trust.TrustRiskLevel;
import com.orderpilot.domain.trust.TrustRiskSignalContribution;
import com.orderpilot.domain.trust.TrustRiskSignalContributionRepository;
import com.orderpilot.domain.trust.TrustSignalSeverity;
import com.orderpilot.domain.trust.TrustTier;
import com.orderpilot.domain.trust.analytics.CounterpartyTrustDashboardView;
import com.orderpilot.domain.trust.analytics.CounterpartyTrustDashboardViewRepository;
import com.orderpilot.domain.trust.analytics.DocumentAnomalyAggregate;
import com.orderpilot.domain.trust.analytics.DocumentAnomalyTrendView;
import com.orderpilot.domain.trust.analytics.DocumentAnomalyTrendViewRepository;
import com.orderpilot.domain.trust.analytics.OutstandingDebtView;
import com.orderpilot.domain.trust.analytics.OutstandingDebtViewRepository;
import com.orderpilot.domain.trust.analytics.TrustReviewQueueView;
import com.orderpilot.domain.trust.analytics.TrustReviewQueueViewRepository;
import com.orderpilot.domain.trust.analytics.TrustRiskDistributionAggregate;
import com.orderpilot.domain.trust.analytics.TrustRiskDistributionView;
import com.orderpilot.domain.trust.analytics.TrustRiskDistributionViewRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-17E Trust Analytics Read Models — projection / rebuild service.
 *
 * <p>This is a CQRS-lite projection layer, NOT a new scoring engine and NOT a replacement for OP-CAP-17D.
 * The OP-CAP-17A/17B/17C/17D operational write models remain the single source of truth; these methods
 * only DERIVE fast, tenant-scoped, idempotent read-model rows from already-persisted records. Projection
 * never mutates any 17A–17D decision and never invents analytics values — every figure is read from a
 * persisted source row.</p>
 *
 * <p>Each rebuild is tenant-scoped and bounded: targeted rebuilds touch one indexed source row (or one
 * day's worth of rows for the period aggregates), and {@link #rebuildAllForTenant(UUID)} batches over a
 * bounded page of recent records — never an unbounded full-tenant scan. Re-running any rebuild produces
 * the same rows (upsert by natural key; trend rows use delete-then-insert per period), so there are no
 * appended duplicates.</p>
 *
 * <p>Future hook: when an outbox/projector runtime exists, OP-CAP-17D decision-created/overridden,
 * OP-CAP-17C obligation transitions, and OP-CAP-17A run/signal completion would call these same methods
 * asynchronously. In this stage they are synchronous, explicit rebuild methods (documented in
 * {@code STAGE_17E_TRUST_ANALYTICS_READ_MODELS.md}).</p>
 */
@Service
public class TrustAnalyticsProjectionService {
  static final ZoneOffset PERIOD_ZONE = ZoneOffset.UTC;
  /** Bounded scan caps for {@link #rebuildAllForTenant(UUID)} — never an unbounded full-tenant scan. */
  static final int REBUILD_DECISION_SCAN_LIMIT = 200;
  static final int REBUILD_OBLIGATION_SCAN_LIMIT = 200;
  private static final List<PaymentObligationStatus> OUTSTANDING_STATUSES = List.of(
      PaymentObligationStatus.OPEN, PaymentObligationStatus.PARTIALLY_PAID,
      PaymentObligationStatus.OVERDUE, PaymentObligationStatus.DISPUTED);

  private final TrustReviewQueueViewRepository reviewQueueViews;
  private final CounterpartyTrustDashboardViewRepository dashboardViews;
  private final OutstandingDebtViewRepository outstandingDebtViews;
  private final DocumentAnomalyTrendViewRepository anomalyTrendViews;
  private final TrustRiskDistributionViewRepository distributionViews;

  private final TrustRiskDecisionRepository decisions;
  private final TrustRiskSignalContributionRepository contributions;
  private final TrustApprovalRequirementRepository approvals;
  private final CounterpartyTrustProfileRepository profiles;
  private final PaymentObligationRepository obligations;
  private final com.orderpilot.domain.trust.DocumentTrustSignalRepository documentSignals;
  private Clock clock;

  public TrustAnalyticsProjectionService(
      TrustReviewQueueViewRepository reviewQueueViews,
      CounterpartyTrustDashboardViewRepository dashboardViews,
      OutstandingDebtViewRepository outstandingDebtViews,
      DocumentAnomalyTrendViewRepository anomalyTrendViews,
      TrustRiskDistributionViewRepository distributionViews,
      TrustRiskDecisionRepository decisions,
      TrustRiskSignalContributionRepository contributions,
      TrustApprovalRequirementRepository approvals,
      CounterpartyTrustProfileRepository profiles,
      PaymentObligationRepository obligations,
      com.orderpilot.domain.trust.DocumentTrustSignalRepository documentSignals,
      Clock clock) {
    this.reviewQueueViews = reviewQueueViews;
    this.dashboardViews = dashboardViews;
    this.outstandingDebtViews = outstandingDebtViews;
    this.anomalyTrendViews = anomalyTrendViews;
    this.distributionViews = distributionViews;
    this.decisions = decisions;
    this.contributions = contributions;
    this.approvals = approvals;
    this.profiles = profiles;
    this.obligations = obligations;
    this.documentSignals = documentSignals;
    this.clock = clock;
  }

  // ----------------------------- review queue -----------------------------

  /**
   * Projects one OP-CAP-17D decision into the review-queue read model. The row is upserted when the
   * decision is queue-worthy (HIGH/CRITICAL, blocking, or human-review-required, and not superseded) and
   * removed otherwise. Returns true when a row is present after projection.
   */
  @Transactional
  public boolean rebuildTrustReviewQueueForDecision(UUID tenantId, UUID trustRiskDecisionId) {
    TrustRiskDecision decision =
        decisions.findByIdAndTenantId(trustRiskDecisionId, tenantId).orElse(null);
    Optional<TrustReviewQueueView> existing =
        reviewQueueViews.findByTenantIdAndTrustRiskDecisionId(tenantId, trustRiskDecisionId);
    if (decision == null || !isQueueWorthy(decision)) {
      existing.ifPresent(reviewQueueViews::delete);
      return false;
    }
    Instant now = clock.instant();
    TrustReviewQueueView row = existing.orElseGet(
        () -> new TrustReviewQueueView(tenantId, trustRiskDecisionId, decision.getCreatedAt()));
    row.apply(
        decision.getSubjectType(), decision.getSubjectId(), decision.getCounterpartyId(),
        decision.getDocumentTrustRunId(), decision.getPaymentObligationId(), decision.getRiskLevel(),
        decision.getRiskScore(), decision.getAction(), decision.isBlocking(),
        decision.isHumanReviewRequired(), latestApprovalStatus(tenantId, trustRiskDecisionId),
        topReasonCode(tenantId, decision), decision.getReasonSummary(), decision.getCreatedAt(),
        decision.getUpdatedAt(), now);
    reviewQueueViews.save(row);
    return true;
  }

  private boolean isQueueWorthy(TrustRiskDecision decision) {
    if (decision.getStatus() == TrustRiskDecisionStatus.SUPERSEDED
        || decision.getStatus() == TrustRiskDecisionStatus.CANCELLED) {
      return false;
    }
    return decision.getRiskLevel().atLeast(TrustRiskLevel.HIGH)
        || decision.isBlocking() || decision.isHumanReviewRequired();
  }

  private String latestApprovalStatus(UUID tenantId, UUID decisionId) {
    List<TrustApprovalRequirement> reqs =
        approvals.findByTenantIdAndTrustRiskDecisionIdOrderByCreatedAtAsc(tenantId, decisionId);
    if (reqs.isEmpty()) {
      return null;
    }
    return reqs.get(reqs.size() - 1).getStatus().name();
  }

  /** Top deterministic reason code: the forced-floor contribution, else the highest-severity one. */
  private String topReasonCode(UUID tenantId, TrustRiskDecision decision) {
    List<TrustRiskSignalContribution> rows =
        contributions.findByTenantIdAndTrustRiskDecisionIdOrderByCreatedAtAsc(tenantId, decision.getId());
    if (rows.isEmpty()) {
      return null;
    }
    return rows.stream()
        .filter(c -> c.getForcedLevel() != null && c.getForcedLevel() == decision.getRiskLevel())
        .map(c -> c.getSignalCode().name())
        .findFirst()
        .orElseGet(() -> rows.stream()
            .max((a, b) -> Integer.compare(a.getSeverity().ordinal(), b.getSeverity().ordinal()))
            .map(c -> c.getSignalCode().name())
            .orElse(null));
  }

  // ----------------------------- counterparty dashboard -----------------------------

  /**
   * Projects the combined OP-CAP-17B profile + OP-CAP-17C obligation aggregates + OP-CAP-17D decision
   * counts for one counterparty into the dashboard read model. Returns false (removing any stale row)
   * when the counterparty has no trust activity at all.
   */
  @Transactional
  public boolean rebuildCounterpartyTrustDashboard(UUID tenantId, UUID counterpartyId) {
    CounterpartyTrustProfile profile =
        profiles.findByTenantIdAndCustomerAccountId(tenantId, counterpartyId).orElse(null);

    long openCount = 0;
    long overdueObCount = 0;
    long paidCount = 0;
    BigDecimal outstanding = zeroMoney();
    for (PaymentObligationStatusAggregate agg : obligations.aggregateByStatus(tenantId, counterpartyId)) {
      PaymentObligationStatus status = agg.getStatus();
      if (status == PaymentObligationStatus.OPEN || status == PaymentObligationStatus.PARTIALLY_PAID) {
        openCount += agg.getObligationCount();
      } else if (status == PaymentObligationStatus.OVERDUE) {
        overdueObCount += agg.getObligationCount();
      } else if (status == PaymentObligationStatus.PAID) {
        paidCount += agg.getObligationCount();
      }
      if (OUTSTANDING_STATUSES.contains(status)) {
        outstanding = outstanding.add(nz(agg.getRemainingAmount()));
      }
    }

    long highRiskDecisions = decisions.countByTenantIdAndCounterpartyIdAndRiskLevelAndStatus(
        tenantId, counterpartyId, TrustRiskLevel.HIGH, TrustRiskDecisionStatus.ACTIVE);
    long criticalRiskDecisions = decisions.countByTenantIdAndCounterpartyIdAndRiskLevelAndStatus(
        tenantId, counterpartyId, TrustRiskLevel.CRITICAL, TrustRiskDecisionStatus.ACTIVE);
    Instant lastHighRiskAt = decisions
        .findFirstByTenantIdAndCounterpartyIdAndRiskLevelOrderByCreatedAtDesc(
            tenantId, counterpartyId, TrustRiskLevel.HIGH)
        .map(TrustRiskDecision::getCreatedAt).orElse(null);
    Instant lastCriticalRiskAt = decisions
        .findFirstByTenantIdAndCounterpartyIdAndRiskLevelOrderByCreatedAtDesc(
            tenantId, counterpartyId, TrustRiskLevel.CRITICAL)
        .map(TrustRiskDecision::getCreatedAt).orElse(null);

    boolean hasActivity = profile != null || openCount > 0 || overdueObCount > 0 || paidCount > 0
        || highRiskDecisions > 0 || criticalRiskDecisions > 0
        || lastHighRiskAt != null || lastCriticalRiskAt != null;
    Optional<CounterpartyTrustDashboardView> existing =
        dashboardViews.findByTenantIdAndCounterpartyId(tenantId, counterpartyId);
    if (!hasActivity) {
      existing.ifPresent(dashboardViews::delete);
      return false;
    }

    // Single-currency outstanding amount only; mixed currencies withhold the amount (no FX conversion).
    List<String> currencies = obligations.findDistinctCurrencies(tenantId, counterpartyId);
    String primaryCurrency;
    BigDecimal outstandingAmount;
    if (currencies.isEmpty()) {
      primaryCurrency = null;
      outstandingAmount = null;
    } else if (currencies.size() == 1) {
      primaryCurrency = currencies.get(0);
      outstandingAmount = outstanding;
    } else {
      primaryCurrency = "MIXED";
      outstandingAmount = null;
    }

    Instant now = clock.instant();
    CounterpartyTrustDashboardView row = existing.orElseGet(
        () -> new CounterpartyTrustDashboardView(tenantId, counterpartyId));
    row.apply(
        profile != null ? profile.getTrustScore() : 50,
        profile != null ? profile.getTrustTier() : TrustTier.UNKNOWN,
        profile != null ? profile.getCompletedOrderCount() : 0,
        profile != null ? profile.getCompletedOrderCount() : 0,
        paidCount,
        profile != null ? profile.getOverduePaymentCount() : 0,
        profile != null ? profile.getDisputedCount() : 0,
        profile != null ? profile.getHighRiskDocumentCount() : 0,
        profile != null ? profile.getCriticalRiskDocumentCount() : 0,
        highRiskDecisions,
        criticalRiskDecisions,
        openCount,
        overdueObCount,
        outstandingAmount,
        primaryCurrency,
        profile != null ? profile.getLastOrderAt() : null,
        profile != null ? profile.getLastPaymentAt() : null,
        lastHighRiskAt,
        lastCriticalRiskAt,
        now);
    dashboardViews.save(row);
    return true;
  }

  // ----------------------------- outstanding debt -----------------------------

  /**
   * Projects one OP-CAP-17C obligation into the outstanding-debt read model. The row is upserted while
   * the obligation still carries exposure (any status except PAID/CANCELLED) and removed otherwise.
   */
  @Transactional
  public boolean rebuildOutstandingDebtViewForObligation(UUID tenantId, UUID paymentObligationId) {
    PaymentObligation o = obligations.findByIdAndTenantId(paymentObligationId, tenantId).orElse(null);
    Optional<OutstandingDebtView> existing =
        outstandingDebtViews.findByTenantIdAndPaymentObligationId(tenantId, paymentObligationId);
    if (o == null || !hasExposure(o.getStatus())) {
      existing.ifPresent(outstandingDebtViews::delete);
      return false;
    }

    TrustRiskDecision linked = decisions
        .findFirstByTenantIdAndPaymentObligationIdAndStatusOrderByCreatedAtDesc(
            tenantId, paymentObligationId, TrustRiskDecisionStatus.ACTIVE)
        .orElse(null);
    UUID orderId = o.getSourceType() == PaymentObligationSourceType.ORDER_MIRROR ? o.getSourceRefId() : null;
    UUID invoiceMirrorId =
        o.getSourceType() == PaymentObligationSourceType.INVOICE_MIRROR ? o.getSourceRefId() : null;

    Instant now = clock.instant();
    OutstandingDebtView row = existing.orElseGet(
        () -> new OutstandingDebtView(tenantId, paymentObligationId, o.getCustomerAccountId(), o.getCreatedAt()));
    row.apply(
        o.getCustomerAccountId(), orderId, invoiceMirrorId, o.getExternalReference(),
        o.getAmountTotal(), o.getAmountPaid(), o.getAmountRemaining(), o.getCurrency(), o.getDueDate(),
        o.getStatus(), o.getRiskLevel(), daysOverdue(o.getDueDate()),
        linked != null ? linked.getId() : null,
        linked != null ? topReasonCode(tenantId, linked) : null,
        o.getCreatedAt(), o.getUpdatedAt(), now);
    outstandingDebtViews.save(row);
    return true;
  }

  private static boolean hasExposure(PaymentObligationStatus status) {
    return status != PaymentObligationStatus.PAID && status != PaymentObligationStatus.CANCELLED;
  }

  private int daysOverdue(LocalDate dueDate) {
    if (dueDate == null) {
      return 0;
    }
    LocalDate today = LocalDate.now(clock.withZone(PERIOD_ZONE));
    long days = ChronoUnit.DAYS.between(dueDate, today);
    return days > 0 ? (int) Math.min(days, Integer.MAX_VALUE) : 0;
  }

  // ----------------------------- document anomaly trends -----------------------------

  /**
   * Rebuilds the document anomaly trend rows for one tenant + daily period via delete-then-insert, so a
   * rebuild is idempotent (no appended duplicates). Returns the number of (signal code, severity) rows
   * projected. {@code counterpartyId} is null in this stage (17A signals are keyed by trust run, not
   * counterparty) — documented limitation.
   */
  @Transactional
  public int rebuildDocumentAnomalyTrends(UUID tenantId, String periodKey) {
    LocalDate date = parsePeriodKey(periodKey);
    Instant start = periodStart(date);
    Instant end = periodEnd(date);

    List<DocumentAnomalyTrendView> stale = anomalyTrendViews.findByTenantIdAndPeriodKey(tenantId, periodKey);
    if (!stale.isEmpty()) {
      anomalyTrendViews.deleteAll(stale);
      anomalyTrendViews.flush();
    }

    Instant now = clock.instant();
    List<DocumentAnomalyAggregate> aggs =
        documentSignals.aggregateAnomaliesByPeriod(tenantId, start, end);
    for (DocumentAnomalyAggregate agg : aggs) {
      long occurrences = agg.getOccurrences();
      long high = agg.getSeverity() == TrustSignalSeverity.HIGH ? occurrences : 0;
      long critical = agg.getSeverity() == TrustSignalSeverity.CRITICAL ? occurrences : 0;
      anomalyTrendViews.save(new DocumentAnomalyTrendView(
          tenantId, periodKey, start, end, agg.getSignalCode(), agg.getSeverity(), null, null,
          occurrences, high, critical, agg.getLatestSeenAt(), now));
    }
    return aggs.size();
  }

  // ----------------------------- risk distribution -----------------------------

  /** Upserts the single OP-CAP-17D risk-distribution row for one tenant + daily period. */
  @Transactional
  public boolean rebuildTrustRiskDistribution(UUID tenantId, String periodKey) {
    LocalDate date = parsePeriodKey(periodKey);
    Instant start = periodStart(date);
    Instant end = periodEnd(date);

    TrustRiskDistributionAggregate agg = decisions.aggregateDistribution(tenantId, start, end);
    Double avg = agg.getAvgRiskScore();
    BigDecimal avgRiskScore = avg == null
        ? BigDecimal.ZERO.setScale(2)
        : BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP);

    Instant now = clock.instant();
    TrustRiskDistributionView row = distributionViews.findByTenantIdAndPeriodKey(tenantId, periodKey)
        .orElseGet(() -> new TrustRiskDistributionView(tenantId, periodKey, start, end));
    row.apply(start, end, agg.getLowCount(), agg.getMediumCount(), agg.getHighCount(),
        agg.getCriticalCount(), agg.getApprovalRequiredCount(), agg.getBlockingCount(),
        agg.getOverrideCount(), avgRiskScore, now);
    distributionViews.save(row);
    return true;
  }

  // ----------------------------- bounded full-tenant rebuild -----------------------------

  /**
   * Bounded best-effort rebuild for one tenant: today's risk distribution + document anomaly trends,
   * plus the review-queue rows for the most recent {@value #REBUILD_DECISION_SCAN_LIMIT} active
   * decisions, the dashboards of the counterparties they touch, and a bounded page of those
   * counterparties' obligations. Full historical backfill across all decisions/obligations is out of
   * scope for this stage (documented) — targeted rebuilds cover individual records precisely.
   */
  @Transactional
  public TrustAnalyticsRebuildResponseDto rebuildAllForTenant(UUID tenantId) {
    Instant now = clock.instant();
    String periodKey = periodKeyFor(now);

    boolean distribution = rebuildTrustRiskDistribution(tenantId, periodKey);
    int trendRows = rebuildDocumentAnomalyTrends(tenantId, periodKey);

    List<TrustRiskDecision> recent = decisions.findByTenantIdAndStatusOrderByCreatedAtDesc(
        tenantId, TrustRiskDecisionStatus.ACTIVE, PageRequest.of(0, REBUILD_DECISION_SCAN_LIMIT));
    int queueRows = 0;
    Set<UUID> counterparties = new LinkedHashSet<>();
    for (TrustRiskDecision decision : recent) {
      if (rebuildTrustReviewQueueForDecision(tenantId, decision.getId())) {
        queueRows++;
      }
      if (decision.getCounterpartyId() != null) {
        counterparties.add(decision.getCounterpartyId());
      }
    }

    int dashboards = 0;
    int debtRows = 0;
    Set<UUID> seenObligations = new LinkedHashSet<>();
    for (UUID counterpartyId : counterparties) {
      if (rebuildCounterpartyTrustDashboard(tenantId, counterpartyId)) {
        dashboards++;
      }
      List<PaymentObligation> obs = obligations.findByTenantIdAndCustomerAccountIdOrderByCreatedAtDesc(
          tenantId, counterpartyId, PageRequest.of(0, REBUILD_OBLIGATION_SCAN_LIMIT));
      for (PaymentObligation o : obs) {
        if (seenObligations.add(o.getId())
            && rebuildOutstandingDebtViewForObligation(tenantId, o.getId())) {
          debtRows++;
        }
      }
    }
    return new TrustAnalyticsRebuildResponseDto(
        periodKey, queueRows, dashboards, debtRows, trendRows, distribution, now);
  }

  // ----------------------------- period helpers -----------------------------

  String periodKeyFor(Instant instant) {
    return LocalDate.ofInstant(instant, PERIOD_ZONE).toString();
  }

  static LocalDate parsePeriodKey(String periodKey) {
    if (periodKey == null || periodKey.isBlank()) {
      throw new IllegalArgumentException("periodKey is required (daily yyyy-MM-dd)");
    }
    try {
      return LocalDate.parse(periodKey.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("periodKey must be a daily yyyy-MM-dd value: " + periodKey);
    }
  }

  private static Instant periodStart(LocalDate date) {
    return date.atStartOfDay(PERIOD_ZONE).toInstant();
  }

  private static Instant periodEnd(LocalDate date) {
    return date.plusDays(1).atStartOfDay(PERIOD_ZONE).toInstant();
  }

  private static BigDecimal zeroMoney() {
    return BigDecimal.ZERO.setScale(4);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? zeroMoney() : value;
  }
}
