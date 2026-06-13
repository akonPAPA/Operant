package com.orderpilot.application.services.trust;

import com.orderpilot.domain.trust.CounterpartyTrustProfile;
import com.orderpilot.domain.trust.TrustRiskLevel;
import com.orderpilot.domain.trust.TrustTier;
import org.springframework.stereotype.Service;

/**
 * OP-CAP-17B Counterparty Trust Profile Foundation.
 *
 * Deterministic, AI-free trust scoring. All scores are clamped to 0..100. Counter contributions are
 * saturated before arithmetic so arbitrarily large {@code long} counters cannot overflow. A
 * CRITICAL/HIGH last risk level applies a tier floor so a single critical signal can never be masked
 * by a high historical trust score (no trust discount over critical evidence).
 */
@Service
public class CounterpartyTrustScoringService {
  private static final int KNOWN_BASE = 70;
  private static final int UNKNOWN_BASE = 50;
  // Cap any single counter's effective magnitude so huge longs cannot overflow the weighted math.
  private static final long COUNTER_CAP = 1_000_000L;

  public ScoreResult score(CounterpartyTrustProfile p) {
    boolean active = p.hasActivity();
    long base = active ? KNOWN_BASE : UNKNOWN_BASE;

    long penalty =
        cap(p.getHighRiskDocumentCount()) * 8L
        + cap(p.getCriticalRiskDocumentCount()) * 20L
        + cap(p.getManualReviewCount()) * 3L
        + cap(p.getRejectedDocumentCount()) * 6L
        + cap(p.getDisputedCount()) * 10L
        + cap(p.getBankAccountChangeCount()) * 5L
        + cap(p.getOverduePaymentCount()) * 7L;
    long reward = Math.min(cap(p.getCompletedOrderCount()), 20L); // bounded positive history reward

    int trustScore = clamp(base - penalty + reward);
    int documentReliabilityScore = documentReliability(p);
    int paymentReliabilityScore = paymentReliability(p);
    int orderPatternScore = orderPattern(p);

    TrustTier tier = tierFromScore(trustScore, active);
    tier = tier.floorWith(floorForRisk(p.getLastRiskLevel()));

    return new ScoreResult(trustScore, tier, documentReliabilityScore, paymentReliabilityScore, orderPatternScore);
  }

  private int documentReliability(CounterpartyTrustProfile p) {
    if (p.getTotalDocumentCount() == 0) {
      return 50;
    }
    long score = 100
        - cap(p.getHighRiskDocumentCount()) * 10L
        - cap(p.getCriticalRiskDocumentCount()) * 30L
        - cap(p.getRejectedDocumentCount()) * 8L
        - cap(p.getWarningDocumentCount()) * 2L;
    return clamp(score);
  }

  private int paymentReliability(CounterpartyTrustProfile p) {
    // Placeholder until OP-CAP-17C: neutral when no payment data is known.
    if (p.getOverduePaymentCount() == 0 && p.getCompletedOrderCount() == 0 && p.getLastPaymentAt() == null) {
      return 50;
    }
    long score = 70 - cap(p.getOverduePaymentCount()) * 10L - cap(p.getDisputedCount()) * 6L;
    return clamp(score);
  }

  private int orderPattern(CounterpartyTrustProfile p) {
    if (p.getCompletedOrderCount() == 0) {
      return 50;
    }
    long score = 50 + Math.min(cap(p.getCompletedOrderCount()), 25L) * 2L;
    return clamp(score);
  }

  private TrustTier tierFromScore(int score, boolean active) {
    if (!active) {
      return TrustTier.UNKNOWN;
    }
    if (score >= 85) {
      return TrustTier.TRUSTED;
    }
    if (score >= 70) {
      return TrustTier.STABLE;
    }
    if (score >= 50) {
      return TrustTier.WATCHLIST;
    }
    return TrustTier.HIGH_RISK;
  }

  // Critical evidence forces at least HIGH_RISK; high evidence forces at least WATCHLIST.
  private TrustTier floorForRisk(TrustRiskLevel lastRiskLevel) {
    if (lastRiskLevel == TrustRiskLevel.CRITICAL) {
      return TrustTier.HIGH_RISK;
    }
    if (lastRiskLevel == TrustRiskLevel.HIGH) {
      return TrustTier.WATCHLIST;
    }
    return TrustTier.UNKNOWN; // no floor
  }

  private long cap(long count) {
    return Math.max(0L, Math.min(count, COUNTER_CAP));
  }

  private int clamp(long score) {
    return (int) Math.max(0L, Math.min(100L, score));
  }

  /** Deterministic scoring output. */
  public record ScoreResult(
      int trustScore,
      TrustTier trustTier,
      int documentReliabilityScore,
      int paymentReliabilityScore,
      int orderPatternScore) {}
}
