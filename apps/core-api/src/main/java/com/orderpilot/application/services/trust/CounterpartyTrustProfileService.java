package com.orderpilot.application.services.trust;

import com.orderpilot.api.dto.TrustDtos.CounterpartyTrustCounts;
import com.orderpilot.api.dto.TrustDtos.CounterpartyTrustProfileView;
import com.orderpilot.api.dto.TrustDtos.CounterpartyTrustSignalView;
import com.orderpilot.api.dto.TrustDtos.CounterpartyTrustSnapshotView;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.application.services.trust.CounterpartyTrustScoringService.ScoreResult;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.trust.CounterpartySignalCode;
import com.orderpilot.domain.trust.CounterpartyTrustProfile;
import com.orderpilot.domain.trust.CounterpartyTrustProfileRepository;
import com.orderpilot.domain.trust.CounterpartyTrustSignal;
import com.orderpilot.domain.trust.CounterpartyTrustSignalRepository;
import com.orderpilot.domain.trust.CounterpartyTrustSnapshot;
import com.orderpilot.domain.trust.CounterpartyTrustSnapshotRepository;
import com.orderpilot.domain.trust.CounterpartyTrustSourceType;
import com.orderpilot.domain.trust.DocumentTrustRun;
import com.orderpilot.domain.trust.TrustRiskLevel;
import com.orderpilot.domain.trust.TrustSignalCode;
import com.orderpilot.domain.trust.TrustTier;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-17B Counterparty Trust Profile Foundation.
 *
 * Tenant-scoped command/query service that accumulates counterparty trust context from OP-CAP-17A
 * document trust results (and future payment/order signals), recomputes a deterministic trust score
 * via {@link CounterpartyTrustScoringService}, appends an evidence snapshot, and exposes bounded
 * read-only views. All mutations go through this service — never AI/bot/frontend/connector directly.
 * No raw document text, account numbers, or bank credentials are stored or exposed.
 */
@Service
public class CounterpartyTrustProfileService {
  public static final int DEFAULT_LIMIT = 25;
  static final int MAX_LIMIT = 100;

  private final CounterpartyTrustProfileRepository profiles;
  private final CounterpartyTrustSignalRepository signals;
  private final CounterpartyTrustSnapshotRepository snapshots;
  private final CounterpartyTrustScoringService scoringService;
  private final AuditEventRepository auditEvents;
  private final JsonSupport jsonSupport;
  private final Clock clock;

  public CounterpartyTrustProfileService(
      CounterpartyTrustProfileRepository profiles,
      CounterpartyTrustSignalRepository signals,
      CounterpartyTrustSnapshotRepository snapshots,
      CounterpartyTrustScoringService scoringService,
      AuditEventRepository auditEvents,
      JsonSupport jsonSupport,
      Clock clock) {
    this.profiles = profiles;
    this.signals = signals;
    this.snapshots = snapshots;
    this.scoringService = scoringService;
    this.auditEvents = auditEvents;
    this.jsonSupport = jsonSupport;
    this.clock = clock;
  }

  /** Returns the tenant-scoped profile, creating a neutral UNKNOWN baseline if absent. */
  @Transactional
  public CounterpartyTrustProfile getOrCreateProfile(UUID tenantId, UUID customerAccountId) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId is required");
    }
    if (customerAccountId == null) {
      throw new IllegalArgumentException("customerAccountId is required");
    }
    return profiles.findByTenantIdAndCustomerAccountId(tenantId, customerAccountId)
        .orElseGet(() -> {
          CounterpartyTrustProfile created = profiles.save(
              new CounterpartyTrustProfile(tenantId, customerAccountId, clock.instant()));
          recordAudit(tenantId, created, "COUNTERPARTY_TRUST_PROFILE_CREATED", null);
          return created;
        });
  }

  /**
   * Feeds a completed OP-CAP-17A document trust run into the counterparty profile. Idempotent on the
   * run id (no double counting). When {@code customerAccountId} is null no global/unscoped profile is
   * created — the call is safely skipped. High/critical risk produces a counterparty signal and is
   * surfaced as approval/blocking via the resulting tier/risk level; low/medium only increments
   * counters.
   */
  @Transactional
  public CounterpartyTrustProfile applyDocumentTrustResult(
      UUID tenantId, UUID customerAccountId, DocumentTrustRun run, List<TrustSignalCode> documentSignalCodes) {
    if (run == null) {
      throw new IllegalArgumentException("document trust run is required");
    }
    if (customerAccountId == null) {
      // No known counterparty: do not create an unscoped/global profile.
      return null;
    }
    if (snapshots.existsByTenantIdAndCustomerAccountIdAndSourceTypeAndSourceRefId(
        tenantId, customerAccountId, CounterpartyTrustSourceType.DOCUMENT_TRUST_RUN, run.getId())) {
      // Already applied this run — return the existing profile without re-counting.
      return profiles.findByTenantIdAndCustomerAccountId(tenantId, customerAccountId).orElse(null);
    }

    Instant now = clock.instant();
    CounterpartyTrustProfile profile = getOrCreateProfile(tenantId, customerAccountId);
    TrustRiskLevel level = run.getRiskLevel();
    profile.recordDocumentRisk(level, run.getId(), now);

    boolean highRisk = level.atLeast(TrustRiskLevel.HIGH);
    if (level == TrustRiskLevel.CRITICAL) {
      saveSignal(tenantId, customerAccountId, CounterpartySignalCode.DOCUMENT_CRITICAL_RISK_SIGNAL,
          TrustRiskLevel.CRITICAL, 20, CounterpartyTrustSourceType.DOCUMENT_TRUST_RUN, run.getId(),
          "Document trust run produced a CRITICAL risk decision.", now);
    } else if (level == TrustRiskLevel.HIGH) {
      saveSignal(tenantId, customerAccountId, CounterpartySignalCode.DOCUMENT_HIGH_RISK_SIGNAL,
          TrustRiskLevel.HIGH, 10, CounterpartyTrustSourceType.DOCUMENT_TRUST_RUN, run.getId(),
          "Document trust run produced a HIGH risk decision.", now);
    }

    // Map the bank-holder-mismatch document signal to counterparty bank stability metadata (count only).
    if (documentSignalCodes != null && documentSignalCodes.contains(TrustSignalCode.BANK_ACCOUNT_HOLDER_MISMATCH)) {
      profile.incrementBankAccountChange(now);
      saveSignal(tenantId, customerAccountId, CounterpartySignalCode.BANK_ACCOUNT_HOLDER_MISMATCH,
          TrustRiskLevel.HIGH, 10, CounterpartyTrustSourceType.DOCUMENT_TRUST_RUN, run.getId(),
          "Document bank account holder differs from the expected counterparty.", now);
      highRisk = true;
    }

    recomputeAndSnapshot(profile, level, CounterpartyTrustSourceType.DOCUMENT_TRUST_RUN, run.getId(),
        "Updated from document trust run " + run.getId(), now);

    recordAudit(tenantId, profile, "COUNTERPARTY_TRUST_PROFILE_UPDATED", level);
    if (highRisk) {
      recordAudit(tenantId, profile, "COUNTERPARTY_HIGH_RISK_SIGNAL_RECORDED", level);
    }
    return profile;
  }

  /**
   * Records a counterparty-level signal from a non-document source (e.g. manual override), recomputes,
   * and snapshots. Evidence is never deleted.
   */
  @Transactional
  public CounterpartyTrustProfile recordCounterpartySignal(
      UUID tenantId, UUID customerAccountId, CounterpartySignalCode code, TrustRiskLevel severity,
      int weight, CounterpartyTrustSourceType sourceType, UUID sourceRefId, String explanation) {
    if (customerAccountId == null) {
      return null;
    }
    Instant now = clock.instant();
    CounterpartyTrustProfile profile = getOrCreateProfile(tenantId, customerAccountId);
    saveSignal(tenantId, customerAccountId, code, severity, weight, sourceType, sourceRefId,
        bounded(explanation), now);
    profile.noteRiskLevel(severity, now);
    recomputeAndSnapshot(profile, severity, sourceType, sourceRefId,
        "Recorded " + code.name(), now);
    recordAudit(tenantId, profile, "COUNTERPARTY_TRUST_PROFILE_UPDATED", severity);
    if (severity != null && severity.atLeast(TrustRiskLevel.HIGH)) {
      recordAudit(tenantId, profile, "COUNTERPARTY_HIGH_RISK_SIGNAL_RECORDED", severity);
    }
    return profile;
  }

  /**
   * OP-CAP-17C narrow hook: applies a payment-obligation-derived counterparty signal. Optionally bumps
   * the overdue/disputed behaviour counters, records an explainable {@code PAYMENT_SIGNAL}-sourced
   * signal, recomputes the deterministic score (so a critical/high payment signal can never be masked
   * by historical trust), appends an evidence snapshot, and audits. When {@code customerAccountId} is
   * null no global/unscoped profile is created — the call is safely skipped. Runs in the caller's
   * transaction so an obligation + trust update commit or roll back together.
   */
  @Transactional
  public CounterpartyTrustProfile applyPaymentObligationSignal(
      UUID tenantId, UUID customerAccountId, CounterpartySignalCode code, TrustRiskLevel severity,
      int weight, UUID sourceRefId, String explanation, boolean incrementOverdue, boolean incrementDisputed) {
    if (customerAccountId == null) {
      return null;
    }
    Instant now = clock.instant();
    CounterpartyTrustProfile profile = getOrCreateProfile(tenantId, customerAccountId);
    if (incrementOverdue) {
      profile.incrementOverduePayment(now);
    }
    if (incrementDisputed) {
      profile.incrementDisputed(now);
    }
    if (code != null) {
      saveSignal(tenantId, customerAccountId, code, severity, weight,
          CounterpartyTrustSourceType.PAYMENT_SIGNAL, sourceRefId, bounded(explanation), now);
      profile.noteRiskLevel(severity, now);
    }
    recomputeAndSnapshot(profile, severity, CounterpartyTrustSourceType.PAYMENT_SIGNAL, sourceRefId,
        "Payment obligation signal" + (code == null ? "" : " " + code.name()), now);
    recordAudit(tenantId, profile, "COUNTERPARTY_TRUST_PROFILE_UPDATED", severity);
    if (severity != null && severity.atLeast(TrustRiskLevel.HIGH)) {
      recordAudit(tenantId, profile, "COUNTERPARTY_HIGH_RISK_SIGNAL_RECORDED", severity);
    }
    return profile;
  }

  /**
   * OP-CAP-17C narrow hook: records the latest payment activity instant on the profile and recomputes
   * the deterministic payment reliability/score, appending an evidence snapshot. No signal is created —
   * receiving a payment is positive/neutral behaviour. Safely skipped for a null counterparty.
   */
  @Transactional
  public CounterpartyTrustProfile recordPaymentReliabilityUpdate(
      UUID tenantId, UUID customerAccountId, Instant paymentReceivedAt) {
    if (customerAccountId == null) {
      return null;
    }
    Instant now = clock.instant();
    CounterpartyTrustProfile profile = getOrCreateProfile(tenantId, customerAccountId);
    profile.recordPaymentActivity(paymentReceivedAt, now);
    recomputeAndSnapshot(profile, profile.getLastRiskLevel(), CounterpartyTrustSourceType.PAYMENT_SIGNAL, null,
        "Payment received", now);
    recordAudit(tenantId, profile, "COUNTERPARTY_TRUST_PROFILE_UPDATED", profile.getLastRiskLevel());
    return profile;
  }

  /** Deterministic, cheap recompute using profile counters only (no historical scan). */
  @Transactional
  public CounterpartyTrustProfile recomputeProfile(UUID tenantId, UUID customerAccountId) {
    CounterpartyTrustProfile profile = profiles.findByTenantIdAndCustomerAccountId(tenantId, customerAccountId)
        .orElseThrow(() -> new NotFoundException("Counterparty trust profile not found"));
    ScoreResult score = scoringService.score(profile);
    profile.applyScores(score.trustScore(), score.trustTier(), score.documentReliabilityScore(),
        score.paymentReliabilityScore(), score.orderPatternScore(), clock.instant());
    return profile;
  }

  private void recomputeAndSnapshot(CounterpartyTrustProfile profile, TrustRiskLevel riskLevel,
      CounterpartyTrustSourceType sourceType, UUID sourceRefId, String reasonSummary, Instant now) {
    ScoreResult score = scoringService.score(profile);
    profile.applyScores(score.trustScore(), score.trustTier(), score.documentReliabilityScore(),
        score.paymentReliabilityScore(), score.orderPatternScore(), now);
    snapshots.save(new CounterpartyTrustSnapshot(
        profile.getTenantId(), profile.getCustomerAccountId(), profile.getId(),
        profile.getTrustScore(), profile.getTrustTier(), riskLevel, bounded(reasonSummary),
        sourceType, sourceRefId, now));
  }

  private void saveSignal(UUID tenantId, UUID customerAccountId, CounterpartySignalCode code,
      TrustRiskLevel severity, int weight, CounterpartyTrustSourceType sourceType, UUID sourceRefId,
      String explanation, Instant now) {
    signals.save(new CounterpartyTrustSignal(tenantId, customerAccountId, code, severity,
        (BigDecimal) null, weight, sourceType, sourceRefId, bounded(explanation), now));
  }

  // ----------------------------- read side -----------------------------

  @Transactional(readOnly = true)
  public CounterpartyTrustProfileView getProfileView(UUID customerAccountId, int signalLimit, int snapshotLimit) {
    UUID tenantId = TenantContext.requireTenantId();
    CounterpartyTrustProfile profile = profiles.findByTenantIdAndCustomerAccountId(tenantId, customerAccountId)
        .orElseThrow(() -> new NotFoundException("Counterparty trust profile not found"));

    List<CounterpartyTrustSignalView> recentSignals = listRecentSignals(tenantId, customerAccountId, signalLimit);
    List<CounterpartyTrustSnapshotView> recentSnapshots = listRecentSnapshots(tenantId, customerAccountId, snapshotLimit);

    return new CounterpartyTrustProfileView(
        profile.getCustomerAccountId(),
        profile.getTrustScore(),
        profile.getTrustTier().name(),
        profile.getDocumentReliabilityScore(),
        profile.getPaymentReliabilityScore(),
        profile.getOrderPatternScore(),
        profile.getLastRiskLevel() == null ? null : profile.getLastRiskLevel().name(),
        new CounterpartyTrustCounts(
            profile.getTotalDocumentCount(),
            profile.getHighRiskDocumentCount(),
            profile.getCriticalRiskDocumentCount(),
            profile.getManualReviewCount(),
            profile.getApprovedOverrideCount(),
            profile.getRejectedDocumentCount(),
            profile.getDisputedCount(),
            profile.getBankAccountChangeCount()),
        recentSignals,
        recentSnapshots);
  }

  @Transactional(readOnly = true)
  public List<CounterpartyTrustSignalView> listRecentSignals(UUID customerAccountId, int limit) {
    return listRecentSignals(TenantContext.requireTenantId(), customerAccountId, limit);
  }

  private List<CounterpartyTrustSignalView> listRecentSignals(UUID tenantId, UUID customerAccountId, int limit) {
    Pageable page = PageRequest.of(0, clampLimit(limit));
    return signals.findByTenantIdAndCustomerAccountIdOrderByCreatedAtDesc(tenantId, customerAccountId, page)
        .stream()
        .map(s -> new CounterpartyTrustSignalView(
            s.getSignalCode().name(), s.getSeverity().name(), s.getExplanation(),
            s.getSourceType().name(), s.getCreatedAt()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<CounterpartyTrustSnapshotView> listRecentSnapshots(UUID customerAccountId, int limit) {
    return listRecentSnapshots(TenantContext.requireTenantId(), customerAccountId, limit);
  }

  private List<CounterpartyTrustSnapshotView> listRecentSnapshots(UUID tenantId, UUID customerAccountId, int limit) {
    Pageable page = PageRequest.of(0, clampLimit(limit));
    return snapshots.findByTenantIdAndCustomerAccountIdOrderByCreatedAtDesc(tenantId, customerAccountId, page)
        .stream()
        .map(s -> new CounterpartyTrustSnapshotView(
            s.getTrustScore(), s.getTrustTier().name(),
            s.getRiskLevel() == null ? null : s.getRiskLevel().name(),
            s.getReasonSummary(), s.getSourceType().name(), s.getCreatedAt()))
        .toList();
  }

  static int clampLimit(int requested) {
    if (requested <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(requested, MAX_LIMIT);
  }

  private String bounded(String value) {
    if (value == null) {
      return null;
    }
    return value.length() <= 280 ? value : value.substring(0, 280);
  }

  private void recordAudit(UUID tenantId, CounterpartyTrustProfile profile, String action, TrustRiskLevel riskLevel) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("customerAccountId", profile.getCustomerAccountId().toString());
    metadata.put("trustScore", profile.getTrustScore());
    metadata.put("trustTier", profile.getTrustTier().name());
    if (riskLevel != null) {
      metadata.put("riskLevel", riskLevel.name());
    }
    AuditEvent event = new AuditEvent(
        tenantId, null, action, "CounterpartyTrustProfile", profile.getId().toString(),
        jsonSupport.writeObject(metadata), clock.instant());
    auditEvents.save(event);
  }
}
