package com.orderpilot.domain.analytics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "roi_assumptions")
public class RoiAssumptions {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false, unique = true) private UUID tenantId;
  @Column(name = "average_manual_handling_minutes_per_request", nullable = false) private BigDecimal averageManualHandlingMinutesPerRequest;
  @Column(name = "average_fully_loaded_operator_hourly_cost", nullable = false) private BigDecimal averageFullyLoadedOperatorHourlyCost;
  @Column(name = "default_currency", nullable = false) private String defaultCurrency;
  @Column(name = "value_attribution_mode", nullable = false) private String valueAttributionMode;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected RoiAssumptions() {}

  public RoiAssumptions(UUID tenantId, BigDecimal minutesPerRequest, BigDecimal hourlyCost, String currency, String attributionMode, Instant now) {
    this.tenantId = tenantId;
    update(minutesPerRequest, hourlyCost, currency, attributionMode, now);
    this.createdAt = now;
  }

  public void update(BigDecimal minutesPerRequest, BigDecimal hourlyCost, String currency, String attributionMode, Instant now) {
    this.averageManualHandlingMinutesPerRequest = minutesPerRequest;
    this.averageFullyLoadedOperatorHourlyCost = hourlyCost;
    this.defaultCurrency = currency;
    this.valueAttributionMode = attributionMode;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public BigDecimal getAverageManualHandlingMinutesPerRequest() { return averageManualHandlingMinutesPerRequest; }
  public BigDecimal getAverageFullyLoadedOperatorHourlyCost() { return averageFullyLoadedOperatorHourlyCost; }
  public String getDefaultCurrency() { return defaultCurrency; }
  public String getValueAttributionMode() { return valueAttributionMode; }
  public Instant getUpdatedAt() { return updatedAt; }
}
