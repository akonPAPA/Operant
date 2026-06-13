package com.orderpilot.application.services.runtime;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * OP-CAP-16D Runtime Guard Live Wiring — read-only, tenant-scoped feature entitlement guard.
 *
 * <p>Delegates to the code-defined {@link RuntimeFeaturePolicy} (permissive by default). It performs
 * no DB write, no AI call, and no external call, and produces only a {@link FeatureEntitlementDecision}
 * — it is the cheapest, first gate in the runtime chain so a tenant lacking a feature is rejected
 * before any quota read, rate budget consumption, or expensive work.
 */
@Service
public class FeatureEntitlementGuard {
  private final RuntimeFeaturePolicy featurePolicy;

  public FeatureEntitlementGuard(RuntimeFeaturePolicy featurePolicy) {
    this.featurePolicy = featurePolicy;
  }

  public FeatureEntitlementDecision checkFeature(UUID tenantId, RuntimeFeatureType featureType) {
    if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
    if (featureType == null) throw new IllegalArgumentException("featureType is required");

    // OP-CAP-16E: delegate to the policy's richer evaluation (persistent policy reports precise
    // reason codes; boolean policies fall back to the default FEATURE_AVAILABLE/FEATURE_NOT_AVAILABLE).
    FeatureEntitlementDecision decision = featurePolicy.evaluate(tenantId, featureType);
    // Normalize the HTTP hint so any denial maps to 403 regardless of the policy implementation.
    int httpHint = decision.available() ? 200 : 403;
    if (httpHint == decision.httpStatusHint()) {
      return decision;
    }
    return new FeatureEntitlementDecision(
        decision.available(), featureType, decision.reasonCode(), httpHint);
  }
}
