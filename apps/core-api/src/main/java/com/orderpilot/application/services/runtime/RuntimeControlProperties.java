package com.orderpilot.application.services.runtime;

/**
 * Bounded developer-friendly defaults for runtime-control admission. These values are intentionally
 * coarse: tenant-specific entitlements/quota/rate policy still come from the existing runtime guard.
 */
public class RuntimeControlProperties {
  private boolean enabled = true;
  private boolean defaultAiEnabled = true;
  private long defaultMaxCostUnitsPerRequest = 10_000L;
  private long defaultDailyCostUnitsPerTenant = 100_000L;
  private long defaultMaxSyncCostUnits = 100L;
  private int defaultBackpressureQueueDepth = 1_000;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isDefaultAiEnabled() {
    return defaultAiEnabled;
  }

  public void setDefaultAiEnabled(boolean defaultAiEnabled) {
    this.defaultAiEnabled = defaultAiEnabled;
  }

  public long getDefaultMaxCostUnitsPerRequest() {
    return defaultMaxCostUnitsPerRequest;
  }

  public void setDefaultMaxCostUnitsPerRequest(long defaultMaxCostUnitsPerRequest) {
    this.defaultMaxCostUnitsPerRequest = Math.max(0L, defaultMaxCostUnitsPerRequest);
  }

  public long getDefaultDailyCostUnitsPerTenant() {
    return defaultDailyCostUnitsPerTenant;
  }

  public void setDefaultDailyCostUnitsPerTenant(long defaultDailyCostUnitsPerTenant) {
    this.defaultDailyCostUnitsPerTenant = Math.max(0L, defaultDailyCostUnitsPerTenant);
  }

  public long getDefaultMaxSyncCostUnits() {
    return defaultMaxSyncCostUnits;
  }

  public void setDefaultMaxSyncCostUnits(long defaultMaxSyncCostUnits) {
    this.defaultMaxSyncCostUnits = Math.max(0L, defaultMaxSyncCostUnits);
  }

  public int getDefaultBackpressureQueueDepth() {
    return defaultBackpressureQueueDepth;
  }

  public void setDefaultBackpressureQueueDepth(int defaultBackpressureQueueDepth) {
    this.defaultBackpressureQueueDepth = Math.max(0, defaultBackpressureQueueDepth);
  }
}
