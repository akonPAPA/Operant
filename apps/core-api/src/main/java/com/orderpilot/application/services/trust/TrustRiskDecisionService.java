package com.orderpilot.application.services.trust;

import com.orderpilot.api.dto.TrustDtos.TrustApprovalRequirementView;
import com.orderpilot.api.dto.TrustDtos.TrustDecisionOverrideView;
import com.orderpilot.api.dto.TrustDtos.TrustRiskDecisionView;
import com.orderpilot.api.dto.TrustDtos.TrustRiskEvaluationResponse;
import com.orderpilot.api.dto.TrustDtos.TrustRiskSignalContributionView;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.payment.PaymentObligation;
import com.orderpilot.domain.payment.PaymentObligationRepository;
import com.orderpilot.domain.payment.PaymentObligationStatus;
import com.orderpilot.domain.trust.CounterpartyTrustProfile;
import com.orderpilot.domain.trust.CounterpartyTrustProfileRepository;
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
import com.orderpilot.domain.trust.TrustRiskDecisionRepository;
import com.orderpilot.domain.trust.TrustRiskDecisionStatus;
import com.orderpilot.domain.trust.TrustRiskLevel;
import com.orderpilot.domain.trust.TrustRiskReasonCode;
import com.orderpilot.domain.trust.TrustRiskSignalContribution;
import com.orderpilot.domain.trust.TrustRiskSignalContributionRepository;
import com.orderpilot.domain.trust.TrustRiskSignalSourceType;
import com.orderpilot.domain.trust.TrustSignalCode;
import com.orderpilot.domain.trust.TrustTier;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-17D Trust Risk Decision Engine.
 *
 * Deterministic, explainable, tenant-scoped risk decision layer. Combines OP-CAP-17A document trust,
 * OP-CAP-17B counterparty trust, OP-CAP-17C payment obligation state, and explicit tenant policy
 * defaults into a single {@link TrustRiskDecision} with one normalized
 * {@link TrustRiskSignalContribution} per fired rule. Scoring is bounded (0..100) and a trust discount
 * can never mask a forced HIGH/CRITICAL floor.
 *
 * <p>This is NOT a legal fraud verdict — it never claims a document is fake. A HIGH/CRITICAL outcome
 * means "high-risk signals detected; approval required before irreversible action." All mutation goes
 * through this backend service only — never AI/bot/frontend/connector. No raw document/OCR/prompt text,
 * bank credentials, or secrets are stored.</p>
 */
@Service
public class TrustRiskDecisionService {
  public static final int DEFAULT_LIMIT = 25;
  static final int MAX_LIMIT = 100;

  // Explicit tenant policy defaults (a per-tenant policy table is OP-CAP-17E+ scope; documented).
  static final BigDecimal HIGH_VALUE_THRESHOLD = new BigDecimal("10000");
  static final boolean POLICY_FORCE_APPROVAL_AT_HIGH_VALUE = true;
  static final int TRUST_DISCOUNT_SCORE_THRESHOLD = 80;
  static final int TRUST_DISCOUNT = 10;

  private final TrustRiskDecisionRepository decisions;
  private final TrustRiskSignalContributionRepository contributions;
  private final TrustApprovalRequirementRepository approvals;
  private final TrustDecisionOverrideRepository overrides;
  private final DocumentTrustRunRepository documentTrustRuns;
  private final DocumentTrustSignalRepository documentTrustSignals;
  private final CounterpartyTrustProfileRepository counterpartyProfiles;
  private final PaymentObligationRepository paymentObligations;
  private final AuditEventRepository auditEvents;
  private final JsonSupport jsonSupport;
  private Clock clock;

  public TrustRiskDecisionService(
      TrustRiskDecisionRepository decisions,
      TrustRiskSignalContributionRepository contributions,
      TrustApprovalRequirementRepository approvals,
      TrustDecisionOverrideRepository overrides,
      DocumentTrustRunRepository documentTrustRuns,
      DocumentTrustSignalRepository documentTrustSignals,
      CounterpartyTrustProfileRepository counterpartyProfiles,
      PaymentObligationRepository paymentObligations,
      AuditEventRepository auditEvents,
      JsonSupport jsonSupport,
      Clock clock) {
    this.decisions = decisions;
    this.contributions = contributions;
    this.approvals = approvals;
    this.overrides = overrides;
    this.documentTrustRuns = documentTrustRuns;
    this.documentTrustSignals = documentTrustSignals;
    this.counterpartyProfiles = counterpartyProfiles;
    this.paymentObligations = paymentObligations;
    this.auditEvents = auditEvents;
    this.jsonSupport = jsonSupport;
    this.clock = clock;
  }

  /**
   * Deterministic evaluation request. {@code tenantId} anchors tenant isolation; all upstream lookups
   * are tenant-scoped. Optional ids point at concrete indexed rows — never an unbounded scan.
   */
  public record EvaluateTrustRiskCommand(
      UUID tenantId,
      String subjectType,
      UUID subjectId,
      UUID documentTrustRunId,
      UUID counterpartyId,
      UUID paymentObligationId,
      UUID validationRunId,
      BigDecimal transactionAmount,
      String currency,
      String businessAction,
      String idempotencyKey,
      UUID actor,
      String correlationId) {}

  // ----------------------------- evaluation -----------------------------

  @Transactional
  public TrustRiskDecision evaluate(EvaluateTrustRiskCommand command) {
    if (command.tenantId() == null) {
      throw new IllegalArgumentException("tenantId is required");
    }
    if (command.subjectType() == null || command.subjectType().isBlank()) {
      throw new IllegalArgumentException("subjectType is required");
    }
    if (command.subjectId() == null) {
      throw new IllegalArgumentException("subjectId is required");
    }
    UUID tenantId = command.tenantId();
    Instant now = clock.instant();
    String idempotencyKey = normalize(command.idempotencyKey());

    // Idempotency: a repeat carrying the same token collapses onto the existing active decision.
    if (idempotencyKey != null) {
      Optional<TrustRiskDecision> existing = decisions
          .findFirstByTenantIdAndIdempotencyKeyAndStatusOrderByCreatedAtDesc(
              tenantId, idempotencyKey, TrustRiskDecisionStatus.ACTIVE);
      if (existing.isPresent()) {
        return existing.get();
      }
    }

    List<ContributionSpec> specs = computeContributions(command, tenantId, now);

    // Bounded weighted score with a trust discount that can never mask a forced floor.
    int rawScore = specs.stream().mapToInt(ContributionSpec::contributionScore).sum();
    int discount = trustDiscount(command, tenantId);
    int score = clamp(rawScore - discount);

    TrustRiskLevel scoreLevel = levelForScore(score);
    TrustRiskLevel forcedFloor = specs.stream()
        .map(ContributionSpec::forcedLevel)
        .filter(java.util.Objects::nonNull)
        .reduce(TrustRiskLevel.LOW, TrustRiskLevel::max);
    TrustRiskLevel finalLevel = scoreLevel.max(forcedFloor);

    ActionPolicy policy = actionPolicyFor(finalLevel);
    String reasonSummary = buildReasonSummary(finalLevel, specs);

    // Supersede the prior active decision for this subject so "latest active" stays single-valued.
    decisions.findFirstByTenantIdAndSubjectTypeAndSubjectIdAndStatusOrderByCreatedAtDesc(
        tenantId, command.subjectType(), command.subjectId(), TrustRiskDecisionStatus.ACTIVE)
        .ifPresent(prior -> {
          prior.markSuperseded(now);
          cancelPendingApprovals(tenantId, prior.getId(), now);
        });

    TrustRiskDecision decision = decisions.save(new TrustRiskDecision(
        tenantId,
        command.subjectType(),
        command.subjectId(),
        command.documentTrustRunId(),
        command.counterpartyId(),
        command.paymentObligationId(),
        command.validationRunId(),
        idempotencyKey,
        finalLevel,
        score,
        policy.action(),
        policy.humanReviewRequired(),
        policy.blocking(),
        specs.size(),
        reasonSummary,
        command.actor(),
        normalize(command.correlationId()),
        now));

    for (ContributionSpec spec : specs) {
      contributions.save(new TrustRiskSignalContribution(
          tenantId, decision.getId(), spec.sourceType(), spec.sourceId(), spec.code(),
          spec.severity(), spec.confidence(), spec.weight(), spec.contributionScore(),
          spec.forcedLevel(), bounded(spec.explanation()), spec.evidenceRef(), now));
    }

    // HIGH/CRITICAL (or tenant-policy-forced approval) create a blocking approval requirement.
    if (finalLevel.atLeast(TrustRiskLevel.HIGH)) {
      TrustRiskReasonCode primary = primaryReason(specs, finalLevel);
      boolean critical = finalLevel == TrustRiskLevel.CRITICAL;
      approvals.save(new TrustApprovalRequirement(
          tenantId, decision.getId(),
          critical ? TrustRiskAction.ESCALATE : TrustRiskAction.REQUIRE_APPROVAL,
          critical ? "TRUST_RISK_OVERRIDE" : "REVIEW_ACTION",
          null, primary, now));
    }

    // Audit-significant when it gates an irreversible action.
    if (finalLevel.atLeast(TrustRiskLevel.HIGH)) {
      recordEvaluationAudit(tenantId, decision, command.actor());
    }
    return decision;
  }

  /** Builds the explainable contribution set from the available upstream evidence. */
  private List<ContributionSpec> computeContributions(EvaluateTrustRiskCommand command, UUID tenantId, Instant now) {
    List<ContributionSpec> specs = new ArrayList<>();

    boolean bankMismatch = false;
    boolean bankChanged = false;
    boolean lowHistoryCounterparty = false;
    boolean highValue = isHighValue(command.transactionAmount());

    // 1) Document trust (17A).
    if (command.documentTrustRunId() != null) {
      DocumentTrustRun run = documentTrustRuns.findByIdAndTenantId(command.documentTrustRunId(), tenantId)
          .orElseThrow(() -> new NotFoundException("Document trust run not found"));
      List<DocumentTrustSignal> docSignals = documentTrustSignals
          .findByTenantIdAndTrustRunIdOrderByCreatedAtAsc(tenantId, run.getId());
      boolean hasFutureDate = docSignals.stream().anyMatch(s -> s.getSignalCode() == TrustSignalCode.DOCUMENT_DATE_IN_FUTURE);
      boolean hasDuplicate = run.isDuplicateDetected()
          || docSignals.stream().anyMatch(s -> s.getSignalCode() == TrustSignalCode.DUPLICATE_DOCUMENT_HASH);
      boolean hasTotalMismatch = docSignals.stream().anyMatch(s -> s.getSignalCode() == TrustSignalCode.DOCUMENT_TOTAL_MATH_MISMATCH);
      bankMismatch = docSignals.stream().anyMatch(s -> s.getSignalCode() == TrustSignalCode.BANK_ACCOUNT_HOLDER_MISMATCH);

      String runRef = "documentTrustRun:" + run.getId();
      if (run.getRiskLevel() == TrustRiskLevel.CRITICAL) {
        specs.add(ContributionSpec.document(TrustRiskReasonCode.DOCUMENT_CRITICAL_SIGNAL, TrustRiskLevel.CRITICAL,
            80, TrustRiskLevel.CRITICAL, run.getId(), runRef,
            "Document trust run produced a CRITICAL risk decision."));
      } else if (run.getRiskLevel() == TrustRiskLevel.HIGH) {
        specs.add(ContributionSpec.document(TrustRiskReasonCode.DOCUMENT_HIGH_RISK_SIGNAL, TrustRiskLevel.HIGH,
            50, TrustRiskLevel.HIGH, run.getId(), runRef,
            "Document trust run produced a HIGH risk decision."));
      }
      if (bankMismatch) {
        specs.add(ContributionSpec.document(TrustRiskReasonCode.BANK_ACCOUNT_HOLDER_MISMATCH, TrustRiskLevel.HIGH,
            30, TrustRiskLevel.HIGH, run.getId(), runRef,
            "Document bank account holder differs from the expected counterparty."));
      }
      if (hasFutureDate) {
        specs.add(ContributionSpec.document(TrustRiskReasonCode.DOCUMENT_DATE_FUTURE_FORCE_HIGH, TrustRiskLevel.HIGH,
            30, TrustRiskLevel.HIGH, run.getId(), runRef,
            "Document date is in the future."));
      }
      if (hasDuplicate && hasTotalMismatch) {
        specs.add(ContributionSpec.document(TrustRiskReasonCode.DUPLICATE_DOCUMENT_WITH_DIFFERENT_AMOUNT, TrustRiskLevel.HIGH,
            40, TrustRiskLevel.HIGH, run.getId(), runRef,
            "Duplicate document content with a non-reconciling total."));
      }
    }

    // 2) Counterparty trust (17B).
    if (command.counterpartyId() != null) {
      Optional<CounterpartyTrustProfile> profileOpt = counterpartyProfiles
          .findByTenantIdAndCustomerAccountId(tenantId, command.counterpartyId());
      if (profileOpt.isPresent()) {
        CounterpartyTrustProfile profile = profileOpt.get();
        String cpRef = "counterparty:" + profile.getCustomerAccountId();
        lowHistoryCounterparty = !profile.hasActivity()
            || profile.getTrustTier() == TrustTier.UNKNOWN
            || (profile.getTotalDocumentCount() == 0 && profile.getCompletedOrderCount() == 0);
        if (profile.getTrustTier() == TrustTier.HIGH_RISK || profile.getTrustTier() == TrustTier.WATCHLIST) {
          specs.add(ContributionSpec.counterparty(TrustRiskReasonCode.COUNTERPARTY_LOW_TRUST, TrustRiskLevel.MEDIUM,
              30, null, profile.getCustomerAccountId(), cpRef,
              "Counterparty trust tier is " + profile.getTrustTier().name() + "."));
        }
        if (profile.getBankAccountChangeCount() > 0) {
          bankChanged = true;
          specs.add(ContributionSpec.counterparty(TrustRiskReasonCode.BANK_ACCOUNT_CHANGED_FROM_HISTORY, TrustRiskLevel.MEDIUM,
              20, null, profile.getCustomerAccountId(), cpRef,
              "Counterparty bank account changed from history."));
        }
        if (lowHistoryCounterparty && highValue) {
          specs.add(ContributionSpec.counterparty(TrustRiskReasonCode.COUNTERPARTY_NEW_HIGH_VALUE, TrustRiskLevel.HIGH,
              25, null, profile.getCustomerAccountId(), cpRef,
              "New/low-history counterparty on a high-value transaction."));
        }
      }
    }

    // 3) Payment obligation / outstanding balance (17C).
    if (command.paymentObligationId() != null) {
      PaymentObligation obligation = paymentObligations.findByIdAndTenantId(command.paymentObligationId(), tenantId)
          .orElseThrow(() -> new NotFoundException("Payment obligation not found"));
      String obRef = "paymentObligation:" + obligation.getId();
      TrustRiskLevel obRisk = obligation.getRiskLevel();

      if (obligation.getStatus() == PaymentObligationStatus.OVERDUE) {
        TrustRiskLevel sev = obRisk.max(TrustRiskLevel.MEDIUM);
        specs.add(ContributionSpec.payment(TrustRiskReasonCode.PAYMENT_OVERDUE, sev,
            scoreForLevel(sev), forcedFor(sev), obligation.getId(), obRef,
            "Payment obligation is overdue (" + obRisk.name() + ")."));
      } else if (obligation.getStatus() == PaymentObligationStatus.PARTIALLY_PAID
          && obligation.getAmountRemaining().signum() > 0) {
        specs.add(ContributionSpec.payment(TrustRiskReasonCode.PAYMENT_PARTIAL_OPEN, TrustRiskLevel.MEDIUM,
            25, null, obligation.getId(), obRef,
            "Payment obligation is partially paid with an open balance."));
      } else if (obligation.getStatus() == PaymentObligationStatus.DISPUTED) {
        specs.add(ContributionSpec.payment(TrustRiskReasonCode.PAYMENT_AMOUNT_MISMATCH, TrustRiskLevel.HIGH,
            50, TrustRiskLevel.HIGH, obligation.getId(), obRef,
            "Payment obligation is disputed."));
      }

      // Transacting for more than is owed is a deterministic amount mismatch.
      if (command.transactionAmount() != null
          && command.transactionAmount().compareTo(obligation.getAmountTotal()) > 0) {
        specs.add(ContributionSpec.payment(TrustRiskReasonCode.PAYMENT_AMOUNT_MISMATCH, TrustRiskLevel.HIGH,
            50, TrustRiskLevel.HIGH, obligation.getId(), obRef,
            "Transaction amount exceeds the recorded obligation total."));
      }

      // Outstanding balance high relative to the transaction amount.
      if (command.transactionAmount() != null && obligation.getAmountRemaining().signum() > 0
          && obligation.getAmountRemaining().compareTo(command.transactionAmount()) >= 0) {
        specs.add(ContributionSpec.payment(TrustRiskReasonCode.OUTSTANDING_BALANCE_HIGH, TrustRiskLevel.HIGH,
            40, TrustRiskLevel.HIGH, obligation.getId(), obRef,
            "Outstanding balance is high relative to the transaction amount."));
      }
    }

    // 4) Policy / forced combination rules (POLICY_RULE source).
    if (lowHistoryCounterparty && highValue && bankMismatch) {
      specs.add(ContributionSpec.policy(TrustRiskReasonCode.COUNTERPARTY_NEW_HIGH_VALUE_BANK_MISMATCH, TrustRiskLevel.CRITICAL,
          0, TrustRiskLevel.CRITICAL, "New/low-history counterparty + high value + bank holder mismatch."));
    }
    if (highValue && POLICY_FORCE_APPROVAL_AT_HIGH_VALUE) {
      specs.add(ContributionSpec.policy(TrustRiskReasonCode.HIGH_VALUE_REQUIRES_APPROVAL, TrustRiskLevel.MEDIUM,
          20, null, "High-value transaction at or above the tenant approval threshold."));
      specs.add(ContributionSpec.policy(TrustRiskReasonCode.TENANT_POLICY_FORCED_APPROVAL, TrustRiskLevel.HIGH,
          0, TrustRiskLevel.HIGH, "Tenant policy forces approval for high-value transactions."));
    }
    // bankChanged is consumed by the BANK_ACCOUNT_CHANGED_FROM_HISTORY contribution above.
    if (bankChanged && bankMismatch) {
      // Rule 1: holder mismatch + changed-from-history => minimum HIGH (both already HIGH-forced via
      // BANK_ACCOUNT_HOLDER_MISMATCH; this policy row documents the combination explicitly).
      specs.add(ContributionSpec.policy(TrustRiskReasonCode.BANK_ACCOUNT_HOLDER_MISMATCH, TrustRiskLevel.HIGH,
          0, TrustRiskLevel.HIGH, "Bank holder mismatch combined with a changed account from history."));
    }
    return specs;
  }

  private int trustDiscount(EvaluateTrustRiskCommand command, UUID tenantId) {
    if (command.counterpartyId() == null) {
      return 0;
    }
    return counterpartyProfiles.findByTenantIdAndCustomerAccountId(tenantId, command.counterpartyId())
        .filter(p -> p.getTrustTier() == TrustTier.TRUSTED || p.getTrustScore() >= TRUST_DISCOUNT_SCORE_THRESHOLD)
        .map(p -> TRUST_DISCOUNT)
        .orElse(0);
  }

  // ----------------------------- manual override -----------------------------

  /**
   * Applies a manual override to a decision. Validates tenant ownership, requires a non-blank reason,
   * preserves original contributions/evidence, records an append-only {@link TrustDecisionOverride},
   * marks the decision OVERRIDDEN in place, and emits an audit event. A CRITICAL decision can never be
   * silently downgraded straight to LOW.
   */
  @Transactional
  public TrustRiskDecision overrideDecision(UUID tenantId, UUID decisionId, TrustRiskLevel newRiskLevel,
      TrustRiskAction newAction, String reason, UUID actor) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId is required");
    }
    if (newRiskLevel == null) {
      throw new IllegalArgumentException("newRiskLevel is required");
    }
    String trimmedReason = reason == null ? null : reason.trim();
    if (trimmedReason == null || trimmedReason.isEmpty()) {
      throw new IllegalArgumentException("override reason is required");
    }
    TrustRiskDecision decision = decisions.findByIdAndTenantId(decisionId, tenantId)
        .orElseThrow(() -> new NotFoundException("Trust risk decision not found"));

    if (decision.getRiskLevel() == TrustRiskLevel.CRITICAL && newRiskLevel == TrustRiskLevel.LOW) {
      // A CRITICAL decision must not be silently downgraded to LOW (no granular security role model
      // yet — conservative service-level guard; see STAGE_17D doc for the future role hook).
      throw new IllegalArgumentException("A CRITICAL decision cannot be overridden directly to LOW");
    }

    Instant now = clock.instant();
    ActionPolicy derived = actionPolicyFor(newRiskLevel);
    TrustRiskAction effectiveAction = newAction != null ? newAction : derived.action();

    TrustRiskLevel previousLevel = decision.getRiskLevel();
    TrustRiskAction previousAction = decision.getAction();

    UUID auditEventId = recordOverrideAudit(tenantId, decision, previousLevel, newRiskLevel,
        previousAction, effectiveAction, trimmedReason, actor);

    TrustDecisionOverride record = overrides.save(new TrustDecisionOverride(
        tenantId, decision.getId(), previousLevel, newRiskLevel, previousAction, effectiveAction,
        bounded(trimmedReason), actor, auditEventId, now));

    decision.applyOverride(newRiskLevel, effectiveAction, derived.humanReviewRequired(), derived.blocking(), now);

    // Manual-override evidence contribution — original contributions are never deleted.
    contributions.save(new TrustRiskSignalContribution(
        tenantId, decision.getId(), TrustRiskSignalSourceType.MANUAL_OVERRIDE, record.getId(),
        TrustRiskReasonCode.MANUAL_OVERRIDE_APPLIED, newRiskLevel, null, 0, 0, null,
        bounded("Manual override: " + trimmedReason), "override:" + record.getId(), now));

    // If the override drops below HIGH the blocking approval requirement no longer applies.
    if (!newRiskLevel.atLeast(TrustRiskLevel.HIGH)) {
      cancelPendingApprovals(tenantId, decision.getId(), now);
    }
    return decision;
  }

  private void cancelPendingApprovals(UUID tenantId, UUID decisionId, Instant now) {
    approvals.findByTenantIdAndTrustRiskDecisionIdAndStatusOrderByCreatedAtAsc(
            tenantId, decisionId, TrustApprovalStatus.PENDING)
        .forEach(req -> req.cancel(now));
  }

  // ----------------------------- read side -----------------------------

  @Transactional(readOnly = true)
  public TrustRiskDecisionView getDecisionView(UUID id) {
    UUID tenantId = TenantContext.requireTenantId();
    TrustRiskDecision decision = decisions.findByIdAndTenantId(id, tenantId)
        .orElseThrow(() -> new NotFoundException("Trust risk decision not found"));
    return toView(tenantId, decision);
  }

  @Transactional(readOnly = true)
  public List<TrustRiskDecisionView> listDecisions(String subjectType, UUID subjectId, String riskLevel,
      String status, int page, int size) {
    UUID tenantId = TenantContext.requireTenantId();
    Pageable pageable = PageRequest.of(Math.max(page, 0), clampLimit(size));
    TrustRiskLevel level = parseEnum(TrustRiskLevel.class, riskLevel);
    TrustRiskDecisionStatus st = parseEnum(TrustRiskDecisionStatus.class, status);

    List<TrustRiskDecision> rows;
    if (subjectType != null && !subjectType.isBlank() && subjectId != null) {
      rows = decisions.findByTenantIdAndSubjectTypeAndSubjectIdOrderByCreatedAtDesc(
          tenantId, subjectType, subjectId, pageable);
    } else if (level != null && st != null) {
      rows = decisions.findByTenantIdAndRiskLevelAndStatusOrderByCreatedAtDesc(tenantId, level, st, pageable);
    } else if (level != null) {
      rows = decisions.findByTenantIdAndRiskLevelOrderByCreatedAtDesc(tenantId, level, pageable);
    } else if (st != null) {
      rows = decisions.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, st, pageable);
    } else {
      rows = decisions.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
    }
    return rows.stream().map(d -> toView(tenantId, d)).toList();
  }

  /** Compact response for an evaluate call. */
  public TrustRiskEvaluationResponse toEvaluationResponse(UUID tenantId, TrustRiskDecision decision) {
    List<TrustRiskSignalContribution> rows = contributions
        .findByTenantIdAndTrustRiskDecisionIdOrderByCreatedAtAsc(tenantId, decision.getId());
    List<String> reasonCodes = rows.stream().map(c -> c.getSignalCode().name()).distinct().toList();
    List<TrustApprovalRequirementView> approvalViews = approvals
        .findByTenantIdAndTrustRiskDecisionIdOrderByCreatedAtAsc(tenantId, decision.getId())
        .stream().map(this::toApprovalView).toList();
    return new TrustRiskEvaluationResponse(
        decision.getId(), decision.getSubjectType(), decision.getSubjectId(),
        decision.getRiskLevel().name(), decision.getRiskScore(), decision.getAction().name(),
        decision.isHumanReviewRequired(), decision.isBlocking(), decision.getReasonSummary(),
        reasonCodes, approvalViews);
  }

  private TrustRiskDecisionView toView(UUID tenantId, TrustRiskDecision decision) {
    List<TrustRiskSignalContributionView> contributionViews = contributions
        .findByTenantIdAndTrustRiskDecisionIdOrderByCreatedAtAsc(tenantId, decision.getId())
        .stream()
        .map(c -> new TrustRiskSignalContributionView(
            c.getId(), c.getSourceType().name(), c.getSourceId(), c.getSignalCode().name(),
            c.getSeverity().name(), c.getConfidence(), c.getWeight(), c.getContributionScore(),
            c.getForcedLevel() == null ? null : c.getForcedLevel().name(),
            c.getExplanation(), c.getEvidenceRef(), c.getCreatedAt()))
        .toList();
    List<TrustApprovalRequirementView> approvalViews = approvals
        .findByTenantIdAndTrustRiskDecisionIdOrderByCreatedAtAsc(tenantId, decision.getId())
        .stream().map(this::toApprovalView).toList();
    List<TrustDecisionOverrideView> overrideViews = overrides
        .findByTenantIdAndTrustRiskDecisionIdOrderByOverriddenAtDesc(tenantId, decision.getId())
        .stream()
        .map(o -> new TrustDecisionOverrideView(
            o.getId(), o.getPreviousRiskLevel().name(), o.getNewRiskLevel().name(),
            o.getPreviousAction().name(), o.getNewAction().name(), o.getReason(),
            o.getOverriddenBy(), o.getOverriddenAt()))
        .toList();
    return new TrustRiskDecisionView(
        decision.getId(), decision.getSubjectType(), decision.getSubjectId(),
        decision.getDocumentTrustRunId(), decision.getCounterpartyId(), decision.getPaymentObligationId(),
        decision.getValidationRunId(), decision.getRiskLevel().name(), decision.getRiskScore(),
        decision.getAction().name(), decision.isHumanReviewRequired(), decision.isBlocking(),
        decision.getSignalCount(), decision.getReasonSummary(), decision.getStatus().name(),
        decision.getCreatedAt(), decision.getUpdatedAt(),
        contributionViews, approvalViews, overrideViews);
  }

  private TrustApprovalRequirementView toApprovalView(TrustApprovalRequirement r) {
    return new TrustApprovalRequirementView(
        r.getId(), r.getRequiredAction().name(), r.getRequiredPermissionCode(), r.getRequiredRoleCode(),
        r.getReasonCode().name(), r.getStatus().name(), r.getCreatedAt(), r.getSatisfiedAt());
  }

  // ----------------------------- deterministic helpers -----------------------------

  static TrustRiskLevel levelForScore(int score) {
    if (score >= 75) {
      return TrustRiskLevel.CRITICAL;
    }
    if (score >= 50) {
      return TrustRiskLevel.HIGH;
    }
    if (score >= 25) {
      return TrustRiskLevel.MEDIUM;
    }
    return TrustRiskLevel.LOW;
  }

  private static int scoreForLevel(TrustRiskLevel level) {
    return switch (level) {
      case CRITICAL -> 80;
      case HIGH -> 50;
      case MEDIUM -> 25;
      case LOW -> 0;
    };
  }

  private static TrustRiskLevel forcedFor(TrustRiskLevel level) {
    return level.atLeast(TrustRiskLevel.HIGH) ? level : null;
  }

  static ActionPolicy actionPolicyFor(TrustRiskLevel level) {
    return switch (level) {
      case LOW -> new ActionPolicy(TrustRiskAction.CONTINUE, false, false);
      case MEDIUM -> new ActionPolicy(TrustRiskAction.CONTINUE_WITH_WARNING, false, false);
      case HIGH -> new ActionPolicy(TrustRiskAction.REQUIRE_APPROVAL, true, true);
      case CRITICAL -> new ActionPolicy(TrustRiskAction.BLOCK_AUTOMATION, true, true);
    };
  }

  private boolean isHighValue(BigDecimal amount) {
    return amount != null && amount.compareTo(HIGH_VALUE_THRESHOLD) >= 0;
  }

  private static int clamp(int score) {
    return Math.max(0, Math.min(100, score));
  }

  private TrustRiskReasonCode primaryReason(List<ContributionSpec> specs, TrustRiskLevel finalLevel) {
    return specs.stream()
        .filter(s -> s.forcedLevel() != null && s.forcedLevel() == finalLevel)
        .map(ContributionSpec::code)
        .findFirst()
        .orElseGet(() -> specs.stream()
            .max((a, b) -> Integer.compare(a.severity().ordinal(), b.severity().ordinal()))
            .map(ContributionSpec::code)
            .orElse(TrustRiskReasonCode.HIGH_VALUE_REQUIRES_APPROVAL));
  }

  private String buildReasonSummary(TrustRiskLevel level, List<ContributionSpec> specs) {
    String codes = specs.stream()
        .map(s -> s.code().name())
        .distinct()
        .limit(6)
        .collect(Collectors.joining(", "));
    String summary = level.name() + " risk" + (codes.isEmpty() ? "" : ": " + codes);
    return bounded(summary);
  }

  private void recordEvaluationAudit(UUID tenantId, TrustRiskDecision decision, UUID actor) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("subjectType", decision.getSubjectType());
    metadata.put("subjectId", decision.getSubjectId().toString());
    metadata.put("riskLevel", decision.getRiskLevel().name());
    metadata.put("riskScore", decision.getRiskScore());
    metadata.put("action", decision.getAction().name());
    metadata.put("blocking", decision.isBlocking());
    metadata.put("humanReviewRequired", decision.isHumanReviewRequired());
    metadata.put("signalCount", decision.getSignalCount());
    auditEvents.save(new AuditEvent(tenantId, actor, "TRUST_RISK_DECISION_EVALUATED",
        "TrustRiskDecision", decision.getId().toString(), jsonSupport.writeObject(metadata), clock.instant()));
  }

  private UUID recordOverrideAudit(UUID tenantId, TrustRiskDecision decision, TrustRiskLevel prevLevel,
      TrustRiskLevel newLevel, TrustRiskAction prevAction, TrustRiskAction newAction, String reason, UUID actor) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("previousRiskLevel", prevLevel.name());
    metadata.put("newRiskLevel", newLevel.name());
    metadata.put("previousAction", prevAction.name());
    metadata.put("newAction", newAction.name());
    metadata.put("reason", bounded(reason));
    AuditEvent event = auditEvents.save(new AuditEvent(tenantId, actor, "TRUST_RISK_DECISION_OVERRIDDEN",
        "TrustRiskDecision", decision.getId().toString(), jsonSupport.writeObject(metadata), clock.instant()));
    return event.getId();
  }

  static int clampLimit(int requested) {
    if (requested <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(requested, MAX_LIMIT);
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, value.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown " + type.getSimpleName() + ": " + value);
    }
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String bounded(String value) {
    if (value == null) {
      return null;
    }
    return value.length() <= 280 ? value : value.substring(0, 280);
  }

  /** Action routing + gate flags for a final risk level. */
  record ActionPolicy(TrustRiskAction action, boolean humanReviewRequired, boolean blocking) {}

  /** Internal deterministic contribution spec before persistence. */
  private record ContributionSpec(
      TrustRiskSignalSourceType sourceType,
      UUID sourceId,
      TrustRiskReasonCode code,
      TrustRiskLevel severity,
      BigDecimal confidence,
      int weight,
      int contributionScore,
      TrustRiskLevel forcedLevel,
      String explanation,
      String evidenceRef) {

    static ContributionSpec document(TrustRiskReasonCode code, TrustRiskLevel severity, int score,
        TrustRiskLevel forced, UUID sourceId, String ref, String explanation) {
      return new ContributionSpec(TrustRiskSignalSourceType.DOCUMENT_TRUST, sourceId, code, severity,
          null, 1, score, forced, explanation, ref);
    }

    static ContributionSpec counterparty(TrustRiskReasonCode code, TrustRiskLevel severity, int score,
        TrustRiskLevel forced, UUID sourceId, String ref, String explanation) {
      return new ContributionSpec(TrustRiskSignalSourceType.COUNTERPARTY_TRUST, sourceId, code, severity,
          null, 1, score, forced, explanation, ref);
    }

    static ContributionSpec payment(TrustRiskReasonCode code, TrustRiskLevel severity, int score,
        TrustRiskLevel forced, UUID sourceId, String ref, String explanation) {
      return new ContributionSpec(TrustRiskSignalSourceType.PAYMENT_OBLIGATION, sourceId, code, severity,
          null, 1, score, forced, explanation, ref);
    }

    static ContributionSpec policy(TrustRiskReasonCode code, TrustRiskLevel severity, int score,
        TrustRiskLevel forced, String explanation) {
      return new ContributionSpec(TrustRiskSignalSourceType.POLICY_RULE, null, code, severity,
          null, 1, score, forced, explanation, "policy:" + code.name());
    }
  }
}
