package com.orderpilot.application.services.trust;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.TrustAnalyticsDtos.CounterpartyTrustDashboardDto;
import com.orderpilot.api.dto.TrustAnalyticsDtos.DocumentAnomalyTrendDto;
import com.orderpilot.api.dto.TrustAnalyticsDtos.OutstandingDebtItemDto;
import com.orderpilot.api.dto.TrustAnalyticsDtos.TrustAnalyticsRebuildResponseDto;
import com.orderpilot.api.dto.TrustAnalyticsDtos.TrustRiskDistributionDto;
import com.orderpilot.api.dto.TrustAnalyticsDtos.TrustReviewQueueItemDto;
import com.orderpilot.application.services.trust.TrustRiskDecisionService.EvaluateTrustRiskCommand;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.payment.PaymentObligation;
import com.orderpilot.domain.payment.PaymentObligationRepository;
import com.orderpilot.domain.payment.PaymentObligationSourceType;
import com.orderpilot.domain.payment.PaymentObligationStatus;
import com.orderpilot.domain.trust.CounterpartyTrustProfile;
import com.orderpilot.domain.trust.CounterpartyTrustProfileRepository;
import com.orderpilot.domain.trust.DocumentTrustDecision;
import com.orderpilot.domain.trust.DocumentTrustRun;
import com.orderpilot.domain.trust.DocumentTrustRunRepository;
import com.orderpilot.domain.trust.DocumentTrustSignal;
import com.orderpilot.domain.trust.DocumentTrustSignalRepository;
import com.orderpilot.domain.trust.TrustRiskAction;
import com.orderpilot.domain.trust.TrustRiskDecision;
import com.orderpilot.domain.trust.TrustRiskLevel;
import com.orderpilot.domain.trust.TrustSignalCode;
import com.orderpilot.domain.trust.TrustSignalSeverity;
import com.orderpilot.domain.trust.TrustTier;
import com.orderpilot.domain.trust.analytics.TrustReviewQueueViewRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * OP-CAP-17E Trust Analytics Read Models — projection idempotency, source-derived figures, tenant
 * isolation, and bounded paginated/filtered reads. Projection is a derived CQRS-lite layer: it reads
 * already-persisted OP-CAP-17A/17B/17C/17D records and never mutates them.
 */
@SpringBootTest
@ActiveProfiles("test")
class TrustAnalyticsProjectionServiceStage17ETest {
  private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-14T12:00:00Z"), ZoneOffset.UTC);
  private static final Instant NOW = FIXED_CLOCK.instant();
  private static final String PERIOD = "2026-06-14";

  @Autowired private TrustAnalyticsProjectionService projection;
  @Autowired private TrustAnalyticsQueryService query;
  @Autowired private TrustRiskDecisionService decisionService;
  @Autowired private DocumentTrustRunRepository documentRuns;
  @Autowired private DocumentTrustSignalRepository documentSignals;
  @Autowired private CounterpartyTrustProfileRepository profiles;
  @Autowired private PaymentObligationRepository obligations;
  @Autowired private TrustReviewQueueViewRepository reviewQueueViews;

  @BeforeEach
  void fixClocks() {
    ReflectionTestUtils.setField(decisionService, "clock", FIXED_CLOCK);
    ReflectionTestUtils.setField(projection, "clock", FIXED_CLOCK);
    ReflectionTestUtils.setField(query, "clock", FIXED_CLOCK);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  // ----------------------------- fixtures -----------------------------

  private DocumentTrustRun saveRun(UUID tenantId, TrustRiskLevel level) {
    return documentRuns.save(new DocumentTrustRun(
        tenantId, UUID.randomUUID(), null, null,
        "0000000000000000000000000000000000000000000000000000000000000000", null,
        DocumentTrustDecision.of(level, level == TrustRiskLevel.LOW ? 10 : 60),
        false, 0, null, null, NOW));
  }

  private CounterpartyTrustProfile saveProfile(UUID tenantId, UUID cp) {
    CounterpartyTrustProfile profile = new CounterpartyTrustProfile(tenantId, cp, NOW);
    profile.recordDocumentRisk(TrustRiskLevel.HIGH, UUID.randomUUID(), NOW);
    profile.recordCompletedOrder(NOW);
    profile.applyScores(72, TrustTier.STABLE, 60, 55, 65, NOW);
    return profiles.save(profile);
  }

  private PaymentObligation saveObligation(UUID tenantId, UUID cp, BigDecimal total,
      PaymentObligationStatus status, TrustRiskLevel risk, BigDecimal paid, LocalDate dueDate) {
    PaymentObligation o = new PaymentObligation(tenantId, cp, PaymentObligationSourceType.ORDER_MIRROR,
        UUID.randomUUID(), "EXT-1", "INV-1", total, "USD", dueDate, NOW, PaymentObligationStatus.OPEN,
        TrustRiskLevel.LOW, NOW);
    if (paid != null && paid.signum() > 0) {
      o.addPayment(paid, NOW, NOW);
    }
    o.applyStatusAndRisk(status, risk, NOW);
    return obligations.save(o);
  }

  private DocumentTrustSignal saveSignal(UUID tenantId, TrustSignalCode code, TrustSignalSeverity sev) {
    return documentSignals.save(new DocumentTrustSignal(tenantId, UUID.randomUUID(), code, sev,
        "field", null, "ref", "explanation", NOW));
  }

  private TrustRiskDecision evaluate(UUID tenantId, UUID docRun, UUID cp, UUID obligation, BigDecimal amount) {
    return decisionService.evaluate(new EvaluateTrustRiskCommand(tenantId, "DOCUMENT", UUID.randomUUID(),
        docRun, cp, obligation, null, amount, "USD", "FINALIZE", null, null, null));
  }

  // ----------------------------- 1. high risk -> review queue -----------------------------

  @Test
  void highRiskDecisionProjectsToReviewQueue() {
    UUID tenantId = UUID.randomUUID();
    TrustRiskDecision d = evaluate(tenantId, saveRun(tenantId, TrustRiskLevel.HIGH).getId(), null, null, null);

    boolean projected = projection.rebuildTrustReviewQueueForDecision(tenantId, d.getId());

    assertThat(projected).isTrue();
    TenantContext.setTenantId(tenantId);
    List<TrustReviewQueueItemDto> queue = query.listReviewQueue(null, null, null, 0, 25);
    assertThat(queue).hasSize(1);
    assertThat(queue.get(0).riskLevel()).isEqualTo("HIGH");
    assertThat(queue.get(0).humanReviewRequired()).isTrue();
    assertThat(queue.get(0).blocking()).isTrue();
    assertThat(queue.get(0).approvalStatus()).isEqualTo("PENDING");
    assertThat(queue.get(0).topReasonCode()).isNotBlank();
  }

  // ----------------------------- 2. critical blocking -----------------------------

  @Test
  void criticalBlockingDecisionProjectsBlockingTrueAndAction() {
    UUID tenantId = UUID.randomUUID();
    TrustRiskDecision d = evaluate(tenantId, saveRun(tenantId, TrustRiskLevel.CRITICAL).getId(), null, null, null);

    projection.rebuildTrustReviewQueueForDecision(tenantId, d.getId());

    TenantContext.setTenantId(tenantId);
    List<TrustReviewQueueItemDto> queue = query.listReviewQueue("CRITICAL", null, true, 0, 25);
    assertThat(queue).hasSize(1);
    assertThat(queue.get(0).blocking()).isTrue();
    assertThat(queue.get(0).action()).isEqualTo(TrustRiskAction.BLOCK_AUTOMATION.name());
  }

  // ----------------------------- 3. override updates queue + distribution -----------------------------

  @Test
  void manualOverrideUpdatesQueueAndIncrementsDistributionOverrideCount() {
    UUID tenantId = UUID.randomUUID();
    TrustRiskDecision d = evaluate(tenantId, saveRun(tenantId, TrustRiskLevel.HIGH).getId(), null, null, null);
    projection.rebuildTrustReviewQueueForDecision(tenantId, d.getId());

    decisionService.overrideDecision(tenantId, d.getId(), TrustRiskLevel.MEDIUM,
        TrustRiskAction.CONTINUE_WITH_WARNING, "Verified with counterparty", UUID.randomUUID());
    projection.rebuildTrustReviewQueueForDecision(tenantId, d.getId());
    projection.rebuildTrustRiskDistribution(tenantId, PERIOD);

    TenantContext.setTenantId(tenantId);
    // The decision dropped below HIGH and is no longer queue-worthy -> the stale row is removed.
    assertThat(query.listReviewQueue(null, null, null, 0, 25)).isEmpty();
    List<TrustRiskDistributionDto> dist = query.listRiskDistribution(PERIOD, null, null, 25);
    assertThat(dist).hasSize(1);
    assertThat(dist.get(0).overrideCount()).isEqualTo(1);
  }

  // ----------------------------- 4. counterparty dashboard combines sources -----------------------------

  @Test
  void counterpartyDashboardCombinesProfileObligationsAndDecisionCounts() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    saveProfile(tenantId, cp);
    saveObligation(tenantId, cp, new BigDecimal("500.00"), PaymentObligationStatus.OVERDUE,
        TrustRiskLevel.HIGH, null, LocalDate.of(2026, 6, 1));
    evaluate(tenantId, saveRun(tenantId, TrustRiskLevel.HIGH).getId(), cp, null, null);

    boolean projected = projection.rebuildCounterpartyTrustDashboard(tenantId, cp);

    assertThat(projected).isTrue();
    TenantContext.setTenantId(tenantId);
    CounterpartyTrustDashboardDto dash = query.getCounterpartyDashboard(cp);
    assertThat(dash.trustScore()).isEqualTo(72);
    assertThat(dash.trustTier()).isEqualTo("STABLE");
    assertThat(dash.highRiskDecisionCount()).isGreaterThanOrEqualTo(1);
    assertThat(dash.overduePaymentObligationCount()).isEqualTo(1);
    assertThat(dash.outstandingAmount()).isEqualByComparingTo("500.00");
    assertThat(dash.primaryCurrency()).isEqualTo("USD");
    assertThat(dash.lastHighRiskAt()).isNotNull();
  }

  // ----------------------------- 5. outstanding debt from obligation -----------------------------

  @Test
  void outstandingDebtProjectionUsesObligationAmountsStatusAndRisk() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    PaymentObligation o = saveObligation(tenantId, cp, new BigDecimal("1000.00"),
        PaymentObligationStatus.OVERDUE, TrustRiskLevel.HIGH, new BigDecimal("250.00"),
        LocalDate.of(2026, 6, 1));

    boolean projected = projection.rebuildOutstandingDebtViewForObligation(tenantId, o.getId());

    assertThat(projected).isTrue();
    TenantContext.setTenantId(tenantId);
    List<OutstandingDebtItemDto> debt = query.listOutstandingDebt(null, null, null, 0, 25);
    assertThat(debt).hasSize(1);
    assertThat(debt.get(0).amountRemaining()).isEqualByComparingTo("750.00");
    assertThat(debt.get(0).status()).isEqualTo("OVERDUE");
    assertThat(debt.get(0).riskLevel()).isEqualTo("HIGH");
    assertThat(debt.get(0).daysOverdue()).isEqualTo(13);
    assertThat(debt.get(0).orderId()).isNotNull();
  }

  @Test
  void paidObligationIsRemovedFromOutstandingDebt() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    PaymentObligation o = saveObligation(tenantId, cp, new BigDecimal("100.00"),
        PaymentObligationStatus.OVERDUE, TrustRiskLevel.HIGH, null, LocalDate.of(2026, 6, 1));
    assertThat(projection.rebuildOutstandingDebtViewForObligation(tenantId, o.getId())).isTrue();

    o.addPayment(new BigDecimal("100.00"), NOW, NOW);
    o.applyStatusAndRisk(PaymentObligationStatus.PAID, TrustRiskLevel.LOW, NOW);
    obligations.save(o);
    boolean stillPresent = projection.rebuildOutstandingDebtViewForObligation(tenantId, o.getId());

    assertThat(stillPresent).isFalse();
    TenantContext.setTenantId(tenantId);
    assertThat(query.listOutstandingDebt(null, null, null, 0, 25)).isEmpty();
  }

  // ----------------------------- 6. document anomaly trends -----------------------------

  @Test
  void documentAnomalyTrendGroupsSignalsByCodeAndSeverity() {
    UUID tenantId = UUID.randomUUID();
    saveSignal(tenantId, TrustSignalCode.DOCUMENT_DATE_IN_FUTURE, TrustSignalSeverity.HIGH);
    saveSignal(tenantId, TrustSignalCode.DOCUMENT_DATE_IN_FUTURE, TrustSignalSeverity.HIGH);
    saveSignal(tenantId, TrustSignalCode.DUPLICATE_DOCUMENT_HASH, TrustSignalSeverity.CRITICAL);

    int rows = projection.rebuildDocumentAnomalyTrends(tenantId, PERIOD);

    assertThat(rows).isEqualTo(2);
    TenantContext.setTenantId(tenantId);
    List<DocumentAnomalyTrendDto> trends = query.listDocumentAnomalies(PERIOD, PERIOD, null, null, 25);
    DocumentAnomalyTrendDto future = trends.stream()
        .filter(t -> t.signalCode().equals(TrustSignalCode.DOCUMENT_DATE_IN_FUTURE.name()))
        .findFirst().orElseThrow();
    assertThat(future.count()).isEqualTo(2);
    assertThat(future.highCount()).isEqualTo(2);
    DocumentAnomalyTrendDto dup = trends.stream()
        .filter(t -> t.signalCode().equals(TrustSignalCode.DUPLICATE_DOCUMENT_HASH.name()))
        .findFirst().orElseThrow();
    assertThat(dup.criticalCount()).isEqualTo(1);
  }

  // ----------------------------- 7. risk distribution -----------------------------

  @Test
  void riskDistributionGroupsDecisionsByLevel() {
    UUID tenantId = UUID.randomUUID();
    evaluate(tenantId, saveRun(tenantId, TrustRiskLevel.HIGH).getId(), null, null, null);
    evaluate(tenantId, saveRun(tenantId, TrustRiskLevel.CRITICAL).getId(), null, null, null);

    projection.rebuildTrustRiskDistribution(tenantId, PERIOD);

    TenantContext.setTenantId(tenantId);
    List<TrustRiskDistributionDto> dist = query.listRiskDistribution(PERIOD, null, null, 25);
    assertThat(dist).hasSize(1);
    assertThat(dist.get(0).highCount()).isEqualTo(1);
    assertThat(dist.get(0).criticalCount()).isEqualTo(1);
    assertThat(dist.get(0).blockingCount()).isEqualTo(2);
    assertThat(dist.get(0).avgRiskScore()).isNotNull();
  }

  // ----------------------------- 8. idempotency -----------------------------

  @Test
  void rebuildIsIdempotentAndDoesNotDuplicate() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    saveProfile(tenantId, cp);
    PaymentObligation o = saveObligation(tenantId, cp, new BigDecimal("200.00"),
        PaymentObligationStatus.OVERDUE, TrustRiskLevel.HIGH, null, LocalDate.of(2026, 6, 1));
    TrustRiskDecision d = evaluate(tenantId, saveRun(tenantId, TrustRiskLevel.HIGH).getId(), cp, o.getId(), null);
    saveSignal(tenantId, TrustSignalCode.DOCUMENT_DATE_IN_FUTURE, TrustSignalSeverity.HIGH);

    for (int i = 0; i < 2; i++) {
      projection.rebuildTrustReviewQueueForDecision(tenantId, d.getId());
      projection.rebuildCounterpartyTrustDashboard(tenantId, cp);
      projection.rebuildOutstandingDebtViewForObligation(tenantId, o.getId());
      projection.rebuildDocumentAnomalyTrends(tenantId, PERIOD);
      projection.rebuildTrustRiskDistribution(tenantId, PERIOD);
    }

    TenantContext.setTenantId(tenantId);
    assertThat(query.listReviewQueue(null, null, null, 0, 25)).hasSize(1);
    assertThat(query.listOutstandingDebt(null, null, null, 0, 25)).hasSize(1);
    assertThat(query.listDocumentAnomalies(PERIOD, PERIOD, null, null, 25)).hasSize(1);
    assertThat(query.listRiskDistribution(PERIOD, null, null, 25)).hasSize(1);
  }

  // ----------------------------- 9. tenant isolation -----------------------------

  @Test
  void rebuildAndReadNeverCrossTenantBoundaries() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TrustRiskDecision a = evaluate(tenantA, saveRun(tenantA, TrustRiskLevel.HIGH).getId(), null, null, null);
    saveSignal(tenantA, TrustSignalCode.DUPLICATE_DOCUMENT_HASH, TrustSignalSeverity.CRITICAL);

    // Projecting under tenant B with tenant A's ids produces nothing (tenant-scoped source lookups).
    assertThat(projection.rebuildTrustReviewQueueForDecision(tenantB, a.getId())).isFalse();
    projection.rebuildDocumentAnomalyTrends(tenantB, PERIOD);
    projection.rebuildTrustReviewQueueForDecision(tenantA, a.getId());

    TenantContext.setTenantId(tenantB);
    assertThat(query.listReviewQueue(null, null, null, 0, 25)).isEmpty();
    assertThat(query.listDocumentAnomalies(PERIOD, PERIOD, null, null, 25)).isEmpty();

    TenantContext.setTenantId(tenantA);
    assertThat(query.listReviewQueue(null, null, null, 0, 25)).hasSize(1);
  }

  // ----------------------------- 10. pagination + filtering -----------------------------

  @Test
  void reviewQueueAndOutstandingDebtSupportPaginationAndFiltering() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    for (int i = 0; i < 3; i++) {
      TrustRiskDecision d = evaluate(tenantId, saveRun(tenantId, TrustRiskLevel.HIGH).getId(), null, null, null);
      projection.rebuildTrustReviewQueueForDecision(tenantId, d.getId());
    }
    TrustRiskDecision critical = evaluate(tenantId, saveRun(tenantId, TrustRiskLevel.CRITICAL).getId(), null, null, null);
    projection.rebuildTrustReviewQueueForDecision(tenantId, critical.getId());

    PaymentObligation overdue = saveObligation(tenantId, cp, new BigDecimal("300.00"),
        PaymentObligationStatus.OVERDUE, TrustRiskLevel.HIGH, null, LocalDate.of(2026, 6, 1));
    PaymentObligation disputed = saveObligation(tenantId, cp, new BigDecimal("400.00"),
        PaymentObligationStatus.DISPUTED, TrustRiskLevel.HIGH, null, null);
    projection.rebuildOutstandingDebtViewForObligation(tenantId, overdue.getId());
    projection.rebuildOutstandingDebtViewForObligation(tenantId, disputed.getId());

    TenantContext.setTenantId(tenantId);
    // Pagination: 4 HIGH/CRITICAL queue rows, page size 2 -> 2 per page.
    assertThat(query.listReviewQueue(null, null, null, 0, 2)).hasSize(2);
    // Filtering: only the CRITICAL row.
    assertThat(query.listReviewQueue("CRITICAL", null, null, 0, 25)).hasSize(1);
    // Filtering outstanding debt by status.
    List<OutstandingDebtItemDto> overdueOnly = query.listOutstandingDebt("OVERDUE", null, null, 0, 25);
    assertThat(overdueOnly).hasSize(1);
    assertThat(overdueOnly.get(0).status()).isEqualTo("OVERDUE");
  }

  // ----------------------------- 11. bounded full-tenant rebuild -----------------------------

  @Test
  void rebuildAllForTenantProjectsRecentRecordsBounded() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    saveProfile(tenantId, cp);
    PaymentObligation o = saveObligation(tenantId, cp, new BigDecimal("500.00"),
        PaymentObligationStatus.OVERDUE, TrustRiskLevel.HIGH, null, LocalDate.of(2026, 6, 1));
    evaluate(tenantId, saveRun(tenantId, TrustRiskLevel.HIGH).getId(), cp, o.getId(), null);
    saveSignal(tenantId, TrustSignalCode.DOCUMENT_DATE_IN_FUTURE, TrustSignalSeverity.HIGH);

    TrustAnalyticsRebuildResponseDto response = projection.rebuildAllForTenant(tenantId);

    assertThat(response.periodKey()).isEqualTo(PERIOD);
    assertThat(response.riskDistributionProjected()).isTrue();
    assertThat(response.reviewQueueRowsProjected()).isGreaterThanOrEqualTo(1);
    assertThat(response.counterpartyDashboardsProjected()).isGreaterThanOrEqualTo(1);
    assertThat(response.outstandingDebtRowsProjected()).isGreaterThanOrEqualTo(1);
    assertThat(response.documentAnomalyTrendRowsProjected()).isGreaterThanOrEqualTo(1);
    assertThat(reviewQueueViews.findByTenantIdOrderByCreatedAtDesc(
        tenantId, org.springframework.data.domain.PageRequest.of(0, 25))).isNotEmpty();
  }
}
