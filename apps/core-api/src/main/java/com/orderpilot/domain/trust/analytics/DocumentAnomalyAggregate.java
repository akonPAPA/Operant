package com.orderpilot.domain.trust.analytics;

import com.orderpilot.domain.trust.TrustSignalCode;
import com.orderpilot.domain.trust.TrustSignalSeverity;
import java.time.Instant;

/**
 * OP-CAP-17E Trust Analytics Read Models.
 *
 * Bounded group-by projection over OP-CAP-17A document trust signals for one tenant + period. At most one
 * row per (signal code, severity) — never an unbounded per-request scan of the signal table.
 */
public interface DocumentAnomalyAggregate {
  TrustSignalCode getSignalCode();

  TrustSignalSeverity getSeverity();

  long getOccurrences();

  Instant getLatestSeenAt();
}
