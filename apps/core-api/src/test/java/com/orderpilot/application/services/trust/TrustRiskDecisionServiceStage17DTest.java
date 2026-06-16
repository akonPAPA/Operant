package com.orderpilot.application.services.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.trust.TrustRiskDecisionService.EvaluateTrustRiskCommand;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
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
import com.orderpilot.domain.trust.TrustApprovalRequirement;
import com.orderpilot.domain.trust.TrustApprovalRequirementRepository;
import com.orderpilot.domain.trust.TrustApprovalStatus;
import com.orderpilot.domain.trust.TrustDecisionOverride;
import com.orderpilot.domain.trust.TrustDecisionOverrideRepository;
import com.orderpilot.domain.trust.TrustRiskAction;
import com.orderpilot.domain.trust.TrustRiskDecision;
import com.orderpilot.domain.trust.TrustRiskDecisionStatus;
import com.orderpilot.domain.trust.TrustRiskLevel;
import com.orderpilot.domain.trust.TrustRiskSignalContribution;
import com.orderpilot.domain.trust.TrustRiskSignalContributionRepository;
import com.orderpilot.domain.trust.TrustSignalCode;
import com.orderpilot.domain.trust.TrustSignalSeverity;
import com.orderpilot.domain.trust.TrustTier;
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
 * OP-CAP-17D Trust Risk Decision Engine — deterministic scoring, forced levels, action policy,
 * approval requirements, manual override, tenant isolation, score bounds, and idempotency.
 */
@SpringBootTest
@ActiveProfiles("test")
class TrustRiskDecisionServiceStage17DTest {
  private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-14T12:00:00Z"), ZoneOffset.UTC);
  private static final Instant NOW = FIXED_CLOCK.instant();

  @Autowired private TrustRiskDecisionService service;
  @Autowired private DocumentTrustRunRepository documentRuns;
  @Autowired private DocumentTrustSignalRepository documentSignals;
  @Autowired private CounterpartyTrustProfileRepository profiles;
  @Autowired private PaymentObligationRepository obligations;
  @Autowired private TrustRiskSignalContributionRepository contributions;
  @Autowired private TrustApprovalRequirementRepository approvals;
  @Autowired private TrustDecisionOverrideRepository overrides;
  @Autowired private AuditEventRepository auditEvents;

  @BeforeEach
  void fixClock() {
    ReflectionTestUtils.setField(service, "clock", FIXED_CLOCK);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  // ----------------------------- fixtures -----------------------------

  private DocumentTrustRun saveRun(UUID tenantId, TrustRiskLevel level, TrustSignalCode... codes) {
    DocumentTrustRun run = documentRuns.save(new DocumentTrustRun(
        tenantId, UUID.randomUUID(), null, null,
        "0000000000000000000000000000000000000000000000000000000000000000", null,
        DocumentTrustDecision.of(level, level == TrustRiskLevel.LOW ? 10 : 60),
        false, codes.length, null, null, NOW));
    for (TrustSignalCode code : codes) {
      documentSignals.save(new DocumentTrustSignal(tenantId, run.getId(), code,
          TrustSignalSeverity.HIGH, "field", null, "ref", "explanation", NOW));
    }
    return run;
  }

  private CounterpartyTrustProfile saveProfile(UUID tenantId, UUID cp, int score, TrustTier tier,
      long bankChanges, boolean withActivity) {
    CounterpartyTrustProfile profile = new CounterpartyTrustProfile(tenantId, cp, NOW);
    if (withActivity) {
      profile.recordDocumentRisk(TrustRiskLevel.LOW, UUID.randomUUID(), NOW);
    }
    for (long i = 0; i < bankChanges; i++) {
      profile.incrementBankAccountChange(NOW);
    }
    profile.applyScores(score, tier, 50, 50, 50, NOW);
    return profiles.save(profile);
  }

  private PaymentObligation saveObligation(UUID tenantId, UUID cp, BigDecimal total,
      PaymentObligationStatus status, TrustRiskLevel risk, BigDecimal paid) {
    PaymentObligation o = new PaymentObligation(tenantId, cp, PaymentObligationSourceType.MANUAL, null,
        "EXT", "INV", total, "USD", LocalDate.of(2026, 6, 1), NOW, PaymentObligationStatus.OPEN,
        TrustRiskLevel.LOW, NOW);
    if (paid != null && paid.signum() > 0) {
      o.addPayment(paid, NOW, NOW);
    }
    o.applyStatusAndRisk(status, risk, NOW);
    return obligations.save(o);
  }

  private EvaluateTrustRiskCommand cmd(UUID tenantId, UUID docRun, UUID cp, UUID obligation, BigDecimal amount) {
    return new EvaluateTrustRiskCommand(tenantId, "DOCUMENT", UUID.randomUUID(), docRun, cp, obligation,
        null, amount, "USD", "FINALIZE", null, null, null);
  }

  // ----------------------------- 1. low risk -----------------------------

  @Test
  void lowRiskInputsCreateLowContinueDecision() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    DocumentTrustRun run = saveRun(tenantId, TrustRiskLevel.LOW);
    saveProfile(tenantId, cp, 90, TrustTier.TRUSTED, 0, true);
    PaymentObligation o = saveObligation(tenantId, cp, new BigDecimal("100.00"),
        PaymentObligationStatus.OPEN, TrustRiskLevel.LOW, null);

    TrustRiskDecision d = service.evaluate(cmd(tenantId, run.getId(), cp, o.getId(), null));

    assertThat(d.getRiskLevel()).isEqualTo(TrustRiskLevel.LOW);
    assertThat(d.getAction()).isEqualTo(TrustRiskAction.CONTINUE);
    assertThat(d.isHumanReviewRequired()).isFalse();
    assertThat(d.isBlocking()).isFalse();
    assertThat(approvals.findByTenantIdAndTrustRiskDecisionIdOrderByCreatedAtAsc(tenantId, d.getId())).isEmpty();
  }

  // ----------------------------- 2. medium risk -----------------------------

  @Test
  void mediumRiskCreatesContinueWithWarningNoApproval() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    PaymentObligation o = saveObligation(tenantId, cp, new BigDecimal("100.00"),
        PaymentObligationStatus.PARTIALLY_PAID, TrustRiskLevel.MEDIUM, new BigDecimal("40.00"));

    TrustRiskDecision d = service.evaluate(cmd(tenantId, null, null, o.getId(), null));

    assertThat(d.getRiskLevel()).isEqualTo(TrustRiskLevel.MEDIUM);
    assertThat(d.getAction()).isEqualTo(TrustRiskAction.CONTINUE_WITH_WARNING);
    assertThat(d.isHumanReviewRequired()).isFalse();
    assertThat(d.isBlocking()).isFalse();
    assertThat(approvals.findByTenantIdAndTrustRiskDecisionIdOrderByCreatedAtAsc(tenantId, d.getId())).isEmpty();
  }

  // ----------------------------- 3. high risk -----------------------------

  @Test
  void highRiskSignalCreatesRequireApprovalBlockingWithRequirement() {
    UUID tenantId = UUID.randomUUID();
    DocumentTrustRun run = saveRun(tenantId, TrustRiskLevel.HIGH);

    TrustRiskDecision d = service.evaluate(cmd(tenantId, run.getId(), null, null, null));

    assertThat(d.getRiskLevel()).isEqualTo(TrustRiskLevel.HIGH);
    assertThat(d.getAction()).isEqualTo(TrustRiskAction.REQUIRE_APPROVAL);
    assertThat(d.isHumanReviewRequired()).isTrue();
    assertThat(d.isBlocking()).isTrue();
    List<TrustApprovalRequirement> reqs = approvals.findByTenantIdAndTrustRiskDecisionIdOrderByCreatedAtAsc(tenantId, d.getId());
    assertThat(reqs).hasSize(1);
    assertThat(reqs.get(0).getRequiredAction()).isEqualTo(TrustRiskAction.REQUIRE_APPROVAL);
    assertThat(reqs.get(0).getStatus()).isEqualTo(TrustApprovalStatus.PENDING);
  }

  // ----------------------------- 4. critical risk -----------------------------

  @Test
  void criticalSignalCreatesBlockAutomationWithEscalation() {
    UUID tenantId = UUID.randomUUID();
    DocumentTrustRun run = saveRun(tenantId, TrustRiskLevel.CRITICAL);

    TrustRiskDecision d = service.evaluate(cmd(tenantId, run.getId(), null, null, null));

    assertThat(d.getRiskLevel()).isEqualTo(TrustRiskLevel.CRITICAL);
    assertThat(d.getAction()).isEqualTo(TrustRiskAction.BLOCK_AUTOMATION);
    assertThat(d.isBlocking()).isTrue();
    List<TrustApprovalRequirement> reqs = approvals.findByTenantIdAndTrustRiskDecisionIdOrderByCreatedAtAsc(tenantId, d.getId());
    assertThat(reqs).hasSize(1);
    assertThat(reqs.get(0).getRequiredAction()).isEqualTo(TrustRiskAction.ESCALATE);
    assertThat(reqs.get(0).getRequiredPermissionCode()).isEqualTo("TRUST_RISK_OVERRIDE");
  }

  // ----------------------------- 5. forced level overrides high trust -----------------------------

  @Test
  void bankMismatchPlusChangedAccountForcesHighEvenWithHighTrust() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    DocumentTrustRun run = saveRun(tenantId, TrustRiskLevel.LOW, TrustSignalCode.BANK_ACCOUNT_HOLDER_MISMATCH);
    saveProfile(tenantId, cp, 95, TrustTier.TRUSTED, 1, true);

    TrustRiskDecision d = service.evaluate(cmd(tenantId, run.getId(), cp, null, null));

    // Score alone (30 + 20 - 10 discount = 40) would be MEDIUM; the forced HIGH floor wins.
    assertThat(d.getRiskLevel()).isEqualTo(TrustRiskLevel.HIGH);
    assertThat(d.getAction()).isEqualTo(TrustRiskAction.REQUIRE_APPROVAL);
    assertThat(d.isBlocking()).isTrue();
  }

  // ----------------------------- 6. critical not hidden by trust discount -----------------------------

  @Test
  void criticalCannotBeHiddenByTrustDiscount() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    DocumentTrustRun run = saveRun(tenantId, TrustRiskLevel.CRITICAL);
    saveProfile(tenantId, cp, 99, TrustTier.TRUSTED, 0, true);

    TrustRiskDecision d = service.evaluate(cmd(tenantId, run.getId(), cp, null, null));

    assertThat(d.getRiskLevel()).isEqualTo(TrustRiskLevel.CRITICAL);
    assertThat(d.isBlocking()).isTrue();
  }

  // ----------------------------- 7. payment obligation risk -----------------------------

  @Test
  void overdueHighObligationContributesHigh() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    PaymentObligation o = saveObligation(tenantId, cp, new BigDecimal("500.00"),
        PaymentObligationStatus.OVERDUE, TrustRiskLevel.HIGH, null);

    TrustRiskDecision d = service.evaluate(cmd(tenantId, null, null, o.getId(), null));

    assertThat(d.getRiskLevel()).isEqualTo(TrustRiskLevel.HIGH);
    assertThat(d.getAction()).isEqualTo(TrustRiskAction.REQUIRE_APPROVAL);
  }

  // ----------------------------- 8. manual override -----------------------------

  @Test
  void manualOverridePreservesEvidenceAndAudits() {
    UUID tenantId = UUID.randomUUID();
    DocumentTrustRun run = saveRun(tenantId, TrustRiskLevel.HIGH);
    TrustRiskDecision d = service.evaluate(cmd(tenantId, run.getId(), null, null, null));
    long contributionsBefore = contributions.countByTenantIdAndTrustRiskDecisionId(tenantId, d.getId());
    UUID actor = UUID.randomUUID();

    TrustRiskDecision overridden = service.overrideDecision(tenantId, d.getId(),
        TrustRiskLevel.MEDIUM, TrustRiskAction.CONTINUE_WITH_WARNING, "Verified with counterparty by phone", actor);

    assertThat(overridden.getStatus()).isEqualTo(TrustRiskDecisionStatus.OVERRIDDEN);
    assertThat(overridden.getRiskLevel()).isEqualTo(TrustRiskLevel.MEDIUM);
    assertThat(overridden.isBlocking()).isFalse();
    List<TrustDecisionOverride> overrideRows = overrides.findByTenantIdAndTrustRiskDecisionIdOrderByOverriddenAtDesc(tenantId, d.getId());
    assertThat(overrideRows).hasSize(1);
    assertThat(overrideRows.get(0).getPreviousRiskLevel()).isEqualTo(TrustRiskLevel.HIGH);
    assertThat(overrideRows.get(0).getNewRiskLevel()).isEqualTo(TrustRiskLevel.MEDIUM);
    // Original contributions preserved; one MANUAL_OVERRIDE evidence row added.
    assertThat(contributions.countByTenantIdAndTrustRiskDecisionId(tenantId, d.getId())).isEqualTo(contributionsBefore + 1);
    // Pending approval requirement is cancelled because the override dropped below HIGH.
    assertThat(approvals.findByTenantIdAndTrustRiskDecisionIdAndStatusOrderByCreatedAtAsc(tenantId, d.getId(), TrustApprovalStatus.PENDING)).isEmpty();
    assertThat(auditEvents.findAll().stream().anyMatch(e -> e.getAction().equals("TRUST_RISK_DECISION_OVERRIDDEN"))).isTrue();
  }

  @Test
  void overrideWithoutReasonIsRejected() {
    UUID tenantId = UUID.randomUUID();
    DocumentTrustRun run = saveRun(tenantId, TrustRiskLevel.HIGH);
    TrustRiskDecision d = service.evaluate(cmd(tenantId, run.getId(), null, null, null));

    assertThatThrownBy(() -> service.overrideDecision(tenantId, d.getId(), TrustRiskLevel.MEDIUM, null, "  ", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reason");
  }

  @Test
  void criticalCannotBeOverriddenDirectlyToLow() {
    UUID tenantId = UUID.randomUUID();
    DocumentTrustRun run = saveRun(tenantId, TrustRiskLevel.CRITICAL);
    TrustRiskDecision d = service.evaluate(cmd(tenantId, run.getId(), null, null, null));

    assertThatThrownBy(() -> service.overrideDecision(tenantId, d.getId(), TrustRiskLevel.LOW, null, "looks fine", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CRITICAL");
  }

  // ----------------------------- 9. tenant isolation -----------------------------

  @Test
  void tenantCannotReadOrOverrideAnotherTenantsDecision() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    DocumentTrustRun run = saveRun(tenantA, TrustRiskLevel.HIGH);
    TrustRiskDecision d = service.evaluate(cmd(tenantA, run.getId(), null, null, null));

    TenantContext.setTenantId(tenantB);
    assertThatThrownBy(() -> service.getDecisionView(d.getId())).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.overrideDecision(tenantB, d.getId(), TrustRiskLevel.MEDIUM, null, "x", null))
        .isInstanceOf(NotFoundException.class);

    TenantContext.setTenantId(tenantA);
    assertThat(service.getDecisionView(d.getId()).riskLevel()).isEqualTo("HIGH");
  }

  // ----------------------------- 10. score bounds -----------------------------

  @Test
  void scoreIsClampedToHundredAndNeverNegative() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    DocumentTrustRun run = saveRun(tenantId, TrustRiskLevel.CRITICAL, TrustSignalCode.BANK_ACCOUNT_HOLDER_MISMATCH,
        TrustSignalCode.DOCUMENT_DATE_IN_FUTURE, TrustSignalCode.DUPLICATE_DOCUMENT_HASH,
        TrustSignalCode.DOCUMENT_TOTAL_MATH_MISMATCH);
    PaymentObligation o = saveObligation(tenantId, cp, new BigDecimal("100.00"),
        PaymentObligationStatus.OVERDUE, TrustRiskLevel.HIGH, null);
    TrustRiskDecision high = service.evaluate(cmd(tenantId, run.getId(), cp, o.getId(), new BigDecimal("50000")));
    assertThat(high.getRiskScore()).isLessThanOrEqualTo(100);
    assertThat(high.getRiskLevel()).isEqualTo(TrustRiskLevel.CRITICAL);

    // Strong trust discount on no material signals cannot push below zero.
    UUID cp2 = UUID.randomUUID();
    saveProfile(tenantId, cp2, 99, TrustTier.TRUSTED, 0, true);
    TrustRiskDecision low = service.evaluate(cmd(tenantId, null, cp2, null, null));
    assertThat(low.getRiskScore()).isGreaterThanOrEqualTo(0);
    assertThat(low.getRiskLevel()).isEqualTo(TrustRiskLevel.LOW);
  }

  // ----------------------------- 11. idempotency -----------------------------

  @Test
  void sameIdempotencyKeyDoesNotDuplicateDecision() {
    UUID tenantId = UUID.randomUUID();
    DocumentTrustRun run = saveRun(tenantId, TrustRiskLevel.HIGH);
    UUID subjectId = UUID.randomUUID();
    EvaluateTrustRiskCommand command = new EvaluateTrustRiskCommand(tenantId, "DOCUMENT", subjectId,
        run.getId(), null, null, null, null, "USD", "FINALIZE", "idem-key-1", null, null);

    TrustRiskDecision first = service.evaluate(command);
    TrustRiskDecision second = service.evaluate(command);

    assertThat(second.getId()).isEqualTo(first.getId());
  }
}
