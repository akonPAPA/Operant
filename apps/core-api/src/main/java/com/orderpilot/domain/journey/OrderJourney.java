package com.orderpilot.domain.journey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-22 — a derived, tenant-scoped projection of where one commercial transaction sits in the
 * lifecycle. The source object (draft quote/order/validation review/reconciliation case) remains the
 * system of record; this row is rebuildable from it plus ingested fulfillment signals.
 */
@Entity
@Table(name = "order_journey")
public class OrderJourney {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false) private JourneySourceType sourceType;
  @Column(name = "source_id", nullable = false) private UUID sourceId;
  @Column(name = "customer_account_id") private UUID customerAccountId;
  @Column(name = "customer_display_name") private String customerDisplayName;
  @Enumerated(EnumType.STRING)
  @Column(name = "current_stage", nullable = false) private MilestoneCode currentStage;
  @Column(name = "current_status", nullable = false) private String currentStatus;
  @Column(name = "risk_level", nullable = false) private String riskLevel;
  @Column(nullable = false) private boolean blocked;
  @Column(name = "customer_visible_status", nullable = false) private String customerVisibleStatus;
  @Column(name = "internal_status", nullable = false) private String internalStatus;
  @Column(name = "last_signal_at") private Instant lastSignalAt;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected OrderJourney() {}

  public OrderJourney(UUID tenantId, JourneySourceType sourceType, UUID sourceId, UUID customerAccountId,
      String customerDisplayName, Instant now) {
    this.tenantId = tenantId;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.customerAccountId = customerAccountId;
    this.customerDisplayName = customerDisplayName;
    this.currentStage = MilestoneCode.REQUEST_RECEIVED;
    this.currentStatus = "Request received";
    this.riskLevel = "LOW";
    this.blocked = false;
    this.customerVisibleStatus = "Received";
    this.internalStatus = "Request received";
    this.createdAt = now;
    this.updatedAt = now;
  }

  /** Apply a recomputed projection state. Source identity and createdAt are immutable. */
  public void applyState(MilestoneCode currentStage, String currentStatus, String riskLevel, boolean blocked,
      String customerVisibleStatus, String internalStatus, UUID customerAccountId, String customerDisplayName,
      Instant lastSignalAt, Instant now) {
    this.currentStage = currentStage;
    this.currentStatus = currentStatus;
    this.riskLevel = riskLevel;
    this.blocked = blocked;
    this.customerVisibleStatus = customerVisibleStatus;
    this.internalStatus = internalStatus;
    if (customerAccountId != null) this.customerAccountId = customerAccountId;
    if (customerDisplayName != null) this.customerDisplayName = customerDisplayName;
    if (lastSignalAt != null) this.lastSignalAt = lastSignalAt;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public JourneySourceType getSourceType() { return sourceType; }
  public UUID getSourceId() { return sourceId; }
  public UUID getCustomerAccountId() { return customerAccountId; }
  public String getCustomerDisplayName() { return customerDisplayName; }
  public MilestoneCode getCurrentStage() { return currentStage; }
  public String getCurrentStatus() { return currentStatus; }
  public String getRiskLevel() { return riskLevel; }
  public boolean isBlocked() { return blocked; }
  public String getCustomerVisibleStatus() { return customerVisibleStatus; }
  public String getInternalStatus() { return internalStatus; }
  public Instant getLastSignalAt() { return lastSignalAt; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
