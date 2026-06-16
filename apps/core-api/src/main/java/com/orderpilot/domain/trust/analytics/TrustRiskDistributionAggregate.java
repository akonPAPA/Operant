package com.orderpilot.domain.trust.analytics;

/**
 * OP-CAP-17E Trust Analytics Read Models.
 *
 * Bounded single-row aggregate over OP-CAP-17D risk decisions for one tenant + period. Computed with a
 * conditional aggregation query so the decision table is scanned at most once per rebuild, never per
 * read request.
 */
public interface TrustRiskDistributionAggregate {
  long getLowCount();

  long getMediumCount();

  long getHighCount();

  long getCriticalCount();

  long getApprovalRequiredCount();

  long getBlockingCount();

  long getOverrideCount();

  Double getAvgRiskScore();
}
