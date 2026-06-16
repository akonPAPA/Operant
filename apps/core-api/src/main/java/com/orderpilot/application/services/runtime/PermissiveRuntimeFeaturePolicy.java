package com.orderpilot.application.services.runtime;

import java.util.UUID;

/**
 * OP-CAP-16D Runtime Guard Live Wiring — default {@link RuntimeFeaturePolicy}: every feature is
 * available to every tenant.
 *
 * <p>This documents and preserves the permissive product default for Stage 16D. It is registered as
 * the default bean via {@code CoreConfiguration} using {@code @ConditionalOnMissingBean}, so a future
 * tenant-entitlement-backed policy can replace it cleanly.
 */
public class PermissiveRuntimeFeaturePolicy implements RuntimeFeaturePolicy {

  @Override
  public boolean isAvailable(UUID tenantId, RuntimeFeatureType feature) {
    return true;
  }
}
