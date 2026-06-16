package com.orderpilot.application.services.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.TrustDtos.CounterpartyTrustProfileView;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.trust.CounterpartyTrustProfile;
import com.orderpilot.domain.trust.CounterpartyTrustProfileRepository;
import com.orderpilot.domain.trust.CounterpartyTrustSignalRepository;
import com.orderpilot.domain.trust.CounterpartyTrustSnapshotRepository;
import com.orderpilot.domain.trust.DocumentTrustCandidate;
import com.orderpilot.domain.trust.DocumentTrustDecision;
import com.orderpilot.domain.trust.DocumentTrustRun;
import com.orderpilot.domain.trust.DocumentTrustRunRepository;
import com.orderpilot.domain.trust.TrustRiskLevel;
import com.orderpilot.domain.trust.TrustSignalCode;
import com.orderpilot.domain.trust.TrustTier;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * OP-CAP-17B Counterparty Trust Profile Foundation.
 * Deterministic scoring, tenant isolation, idempotency, risk routing, and OP-CAP-17A integration.
 */
@SpringBootTest
@ActiveProfiles("test")
class CounterpartyTrustProfileServiceStage17BTest {
  private static final Instant NOW = Instant.parse("2026-06-13T00:00:00Z");

  @Autowired private CounterpartyTrustProfileService profileService;
  @Autowired private CounterpartyTrustScoringService scoringService;
  @Autowired private DocumentTrustService documentTrustService;
  @Autowired private CounterpartyTrustProfileRepository profiles;
  @Autowired private CounterpartyTrustSignalRepository signals;
  @Autowired private CounterpartyTrustSnapshotRepository snapshots;
  @Autowired private DocumentTrustRunRepository runs;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private DocumentTrustRun persistRun(UUID tenantId, TrustRiskLevel level) {
    DocumentTrustDecision decision = DocumentTrustDecision.of(level, 50);
    DocumentTrustRun run = new DocumentTrustRun(
        tenantId, UUID.randomUUID(), null, null, "sha-" + UUID.randomUUID(), null,
        decision, false, 0, null, null, NOW);
    return runs.save(run);
  }

  // ----------------------------- scoring -----------------------------

  @Test
  void newProfileStartsWithBaseScoreAndUnknownTier() {
    UUID tenantId = UUID.randomUUID();
    CounterpartyTrustProfile profile = profileService.getOrCreateProfile(tenantId, UUID.randomUUID());

    assertThat(profile.getTrustScore()).isEqualTo(50);
    assertThat(profile.getTrustTier()).isEqualTo(TrustTier.UNKNOWN);
    assertThat(profile.getDocumentReliabilityScore()).isEqualTo(50);
    assertThat(profile.getPaymentReliabilityScore()).isEqualTo(50);
    assertThat(profile.getOrderPatternScore()).isEqualTo(50);
  }

  @Test
  void highRiskDocumentLowersScoreAndCreatesSnapshot() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    DocumentTrustRun run = persistRun(tenantId, TrustRiskLevel.HIGH);

    CounterpartyTrustProfile profile = profileService.applyDocumentTrustResult(
        tenantId, cp, run, List.of());

    assertThat(profile.getTrustScore()).isLessThan(70);
    assertThat(profile.getHighRiskDocumentCount()).isEqualTo(1);
    assertThat(profile.getLastRiskLevel()).isEqualTo(TrustRiskLevel.HIGH);
    assertThat(snapshots.findByTenantIdAndCustomerAccountIdOrderByCreatedAtDesc(
        tenantId, cp, org.springframework.data.domain.PageRequest.of(0, 25))).hasSize(1);
    assertThat(signals.findByTenantIdAndCustomerAccountIdOrderByCreatedAtDesc(
        tenantId, cp, org.springframework.data.domain.PageRequest.of(0, 25)))
        .extracting(s -> s.getSignalCode().name())
        .contains("DOCUMENT_HIGH_RISK_SIGNAL");
  }

  @Test
  void criticalDocumentCannotBeHiddenByPositiveHistory() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    // Build strong positive history (many completed orders) -> high score / TRUSTED.
    CounterpartyTrustProfile profile = profileService.getOrCreateProfile(tenantId, cp);
    CounterpartyTrustProfile managed = profiles.findByTenantIdAndCustomerAccountId(tenantId, cp).orElseThrow();
    for (int i = 0; i < 20; i++) {
      managed.recordCompletedOrder(NOW);
    }
    profiles.save(managed);
    CounterpartyTrustProfile recomputed = profileService.recomputeProfile(tenantId, cp);
    assertThat(recomputed.getTrustTier()).isIn(TrustTier.TRUSTED, TrustTier.STABLE);

    // A single CRITICAL document must force the tier to HIGH_RISK regardless of history.
    DocumentTrustRun critical = persistRun(tenantId, TrustRiskLevel.CRITICAL);
    CounterpartyTrustProfile after = profileService.applyDocumentTrustResult(tenantId, cp, critical, List.of());

    assertThat(after.getLastRiskLevel()).isEqualTo(TrustRiskLevel.CRITICAL);
    assertThat(after.getTrustTier()).isEqualTo(TrustTier.HIGH_RISK);
    assertThat(after.getCriticalRiskDocumentCount()).isEqualTo(1);
  }

  @Test
  void lowMediumIncrementsCountersWithoutBlocking() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    DocumentTrustRun medium = persistRun(tenantId, TrustRiskLevel.MEDIUM);

    CounterpartyTrustProfile profile = profileService.applyDocumentTrustResult(tenantId, cp, medium, List.of());

    assertThat(profile.getWarningDocumentCount()).isEqualTo(1);
    assertThat(profile.getTotalDocumentCount()).isEqualTo(1);
    assertThat(profile.getLastRiskLevel()).isEqualTo(TrustRiskLevel.MEDIUM);
    assertThat(profile.getTrustTier()).isNotEqualTo(TrustTier.HIGH_RISK);
    // No high/critical counterparty signal is produced for medium.
    assertThat(signals.findByTenantIdAndCustomerAccountIdOrderByCreatedAtDesc(
        tenantId, cp, org.springframework.data.domain.PageRequest.of(0, 25))).isEmpty();
  }

  @Test
  void scoreIsClampedToZeroFloor() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    for (int i = 0; i < 10; i++) {
      profileService.applyDocumentTrustResult(tenantId, cp, persistRun(tenantId, TrustRiskLevel.CRITICAL), List.of());
    }
    CounterpartyTrustProfile profile = profiles.findByTenantIdAndCustomerAccountId(tenantId, cp).orElseThrow();

    assertThat(profile.getTrustScore()).isBetween(0, 100);
    assertThat(profile.getTrustScore()).isZero();
    assertThat(profile.getTrustTier()).isEqualTo(TrustTier.HIGH_RISK);
  }

  @Test
  void largeCountersDoNotOverflow() {
    CounterpartyTrustProfile profile = new CounterpartyTrustProfile(UUID.randomUUID(), UUID.randomUUID(), NOW);
    ReflectionTestUtils.setField(profile, "highRiskDocumentCount", Long.MAX_VALUE);
    ReflectionTestUtils.setField(profile, "completedOrderCount", Long.MAX_VALUE);
    ReflectionTestUtils.setField(profile, "totalDocumentCount", Long.MAX_VALUE);

    CounterpartyTrustScoringService.ScoreResult result = scoringService.score(profile);

    assertThat(result.trustScore()).isBetween(0, 100);
    assertThat(result.documentReliabilityScore()).isBetween(0, 100);
  }

  @Test
  void repeatedSameDocumentRunDoesNotDoubleCount() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    DocumentTrustRun run = persistRun(tenantId, TrustRiskLevel.HIGH);

    profileService.applyDocumentTrustResult(tenantId, cp, run, List.of());
    profileService.applyDocumentTrustResult(tenantId, cp, run, List.of());

    CounterpartyTrustProfile profile = profiles.findByTenantIdAndCustomerAccountId(tenantId, cp).orElseThrow();
    assertThat(profile.getTotalDocumentCount()).isEqualTo(1);
    assertThat(snapshots.findByTenantIdAndCustomerAccountIdOrderByCreatedAtDesc(
        tenantId, cp, org.springframework.data.domain.PageRequest.of(0, 25))).hasSize(1);
  }

  @Test
  void unknownCounterpartyDoesNotCreateGlobalProfile() {
    UUID tenantId = UUID.randomUUID();
    DocumentTrustRun run = persistRun(tenantId, TrustRiskLevel.HIGH);

    CounterpartyTrustProfile result = profileService.applyDocumentTrustResult(tenantId, null, run, List.of());

    assertThat(result).isNull();
    assertThat(profiles.findAll()).noneMatch(p -> p.getTenantId().equals(tenantId));
  }

  // ----------------------------- isolation / bounding -----------------------------

  @Test
  void uniqueProfilePerTenantAndCounterparty() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    CounterpartyTrustProfile first = profileService.getOrCreateProfile(tenantId, cp);
    CounterpartyTrustProfile second = profileService.getOrCreateProfile(tenantId, cp);

    assertThat(second.getId()).isEqualTo(first.getId());
  }

  @Test
  void sameCounterpartyInTwoTenantsIsIsolated() {
    UUID cp = UUID.randomUUID();
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    CounterpartyTrustProfile a = profileService.getOrCreateProfile(tenantA, cp);
    CounterpartyTrustProfile b = profileService.getOrCreateProfile(tenantB, cp);

    assertThat(a.getId()).isNotEqualTo(b.getId());
    assertThat(profiles.findByTenantIdAndCustomerAccountId(tenantA, cp).orElseThrow().getId()).isEqualTo(a.getId());
    assertThat(profiles.findByTenantIdAndCustomerAccountId(tenantB, cp).orElseThrow().getId()).isEqualTo(b.getId());
  }

  @Test
  void listRecentSignalsIsTenantScopedAndBounded() {
    UUID tenantA = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    for (int i = 0; i < 3; i++) {
      profileService.applyDocumentTrustResult(tenantA, cp, persistRun(tenantA, TrustRiskLevel.HIGH), List.of());
    }

    TenantContext.setTenantId(tenantA);
    assertThat(profileService.listRecentSignals(cp, 2)).hasSize(2); // bounded by limit

    TenantContext.setTenantId(UUID.randomUUID());
    assertThat(profileService.listRecentSignals(cp, 25)).isEmpty(); // other tenant sees nothing
  }

  @Test
  void listRecentSnapshotsIsTenantScopedAndBounded() {
    UUID tenantA = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    for (int i = 0; i < 3; i++) {
      profileService.applyDocumentTrustResult(tenantA, cp, persistRun(tenantA, TrustRiskLevel.HIGH), List.of());
    }

    TenantContext.setTenantId(tenantA);
    assertThat(profileService.listRecentSnapshots(cp, 2)).hasSize(2);

    TenantContext.setTenantId(UUID.randomUUID());
    assertThat(profileService.listRecentSnapshots(cp, 25)).isEmpty();
  }

  @Test
  void profileViewForTenantACannotBeReadByTenantB() {
    UUID tenantA = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    profileService.getOrCreateProfile(tenantA, cp);

    TenantContext.setTenantId(tenantA);
    assertThat(profileService.getProfileView(cp, 25, 25).counterpartyId()).isEqualTo(cp);

    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> profileService.getProfileView(cp, 25, 25))
        .isInstanceOf(NotFoundException.class);
  }

  // ----------------------------- OP-CAP-17A integration -----------------------------

  @Test
  void documentTrustRunWithKnownCounterpartyUpdatesProfile() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // Bank holder mismatch => HIGH document risk run.
    DocumentTrustCandidate candidate = new DocumentTrustCandidate(
        null, null, null, null, "doc-content", 1024L, 1, "Beta LLC", "Acme Trading", null, null, null);

    DocumentTrustRun run = documentTrustService.evaluate(tenantId, UUID.randomUUID(), null, cp, candidate);
    assertThat(run.getRiskLevel()).isEqualTo(TrustRiskLevel.HIGH);

    CounterpartyTrustProfileView view = profileService.getProfileView(cp, 25, 25);
    assertThat(view.counts().totalDocumentCount()).isEqualTo(1);
    assertThat(view.counts().highRiskDocumentCount()).isEqualTo(1);
    assertThat(view.counts().bankAccountChangeCount()).isEqualTo(1);
    assertThat(view.recentSignals())
        .extracting(s -> s.signalCode())
        .contains("DOCUMENT_HIGH_RISK_SIGNAL", "BANK_ACCOUNT_HOLDER_MISMATCH");
    assertThat(view.lastRiskLevel()).isEqualTo("HIGH");
  }

  @Test
  void documentTrustRunWithoutCounterpartyDoesNotCreateProfile() {
    UUID tenantId = UUID.randomUUID();
    DocumentTrustCandidate candidate = new DocumentTrustCandidate(
        null, null, null, null, "no-cp-content", 1024L, 1, "Beta LLC", "Acme Trading", null, null, null);

    // 4-arg overload: no counterparty supplied.
    documentTrustService.evaluate(tenantId, UUID.randomUUID(), null, candidate);

    assertThat(profiles.findAll()).noneMatch(p -> p.getTenantId().equals(tenantId));
  }

  @Test
  void limitIsClampedToSafeBounds() {
    assertThat(CounterpartyTrustProfileService.clampLimit(0)).isEqualTo(25);   // default
    assertThat(CounterpartyTrustProfileService.clampLimit(-5)).isEqualTo(25);  // non-positive -> default
    assertThat(CounterpartyTrustProfileService.clampLimit(10)).isEqualTo(10);  // pass-through
    assertThat(CounterpartyTrustProfileService.clampLimit(9999)).isEqualTo(100); // max cap
  }

  @Test
  void documentSignalCodesAreMappedToCounterpartySignals() {
    UUID tenantId = UUID.randomUUID();
    UUID cp = UUID.randomUUID();
    DocumentTrustRun run = persistRun(tenantId, TrustRiskLevel.HIGH);

    profileService.applyDocumentTrustResult(
        tenantId, cp, run, List.of(TrustSignalCode.BANK_ACCOUNT_HOLDER_MISMATCH));

    CounterpartyTrustProfile profile = profiles.findByTenantIdAndCustomerAccountId(tenantId, cp).orElseThrow();
    assertThat(profile.getBankAccountChangeCount()).isEqualTo(1);
  }
}
