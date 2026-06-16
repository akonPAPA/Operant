package com.orderpilot.domain.trust;

/**
 * OP-CAP-17B Counterparty Trust Profile Foundation.
 *
 * Deterministic, explainable trust tier for a tenant-scoped counterparty. This is a business signal
 * for operator review, never a legal or fraud verdict. {@code riskRank} orders tiers from least to
 * most risky so a higher-risk floor (e.g. a CRITICAL last signal) can never be masked by a high
 * historical trust score. {@code UNKNOWN} means there is not yet enough activity to judge.
 */
public enum TrustTier {
  TRUSTED(0),
  STABLE(1),
  WATCHLIST(2),
  HIGH_RISK(3),
  UNKNOWN(-1);

  private final int riskRank;

  TrustTier(int riskRank) {
    this.riskRank = riskRank;
  }

  public int riskRank() {
    return riskRank;
  }

  /** Returns the riskier of the two tiers (UNKNOWN never overrides a concrete tier). */
  public TrustTier floorWith(TrustTier other) {
    if (this == UNKNOWN) {
      return other;
    }
    if (other == UNKNOWN) {
      return this;
    }
    return other.riskRank > this.riskRank ? other : this;
  }
}
