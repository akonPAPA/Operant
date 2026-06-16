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

/** OP-CAP-22 — one derived milestone row for a journey. Rebuilt on every projection refresh. */
@Entity
@Table(name = "order_journey_milestone")
public class OrderJourneyMilestone {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "journey_id", nullable = false) private UUID journeyId;
  @Enumerated(EnumType.STRING)
  @Column(name = "milestone_code", nullable = false) private MilestoneCode milestoneCode;
  @Column(name = "milestone_label", nullable = false) private String milestoneLabel;
  @Enumerated(EnumType.STRING)
  @Column(name = "milestone_state", nullable = false) private MilestoneState milestoneState;
  @Enumerated(EnumType.STRING)
  @Column(name = "evidence_level", nullable = false) private EvidenceLevel evidenceLevel;
  @Column(name = "occurred_at") private Instant occurredAt;
  @Column(name = "estimated_at") private Instant estimatedAt;
  @Column(name = "source_type") private String sourceType;
  @Column(name = "source_ref") private String sourceRef;
  @Column(name = "customer_visible", nullable = false) private boolean customerVisible;
  @Column(name = "sort_order", nullable = false) private int sortOrder;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected OrderJourneyMilestone() {}

  public OrderJourneyMilestone(UUID tenantId, UUID journeyId, MilestoneCode milestoneCode,
      MilestoneState milestoneState, EvidenceLevel evidenceLevel, Instant occurredAt, Instant estimatedAt,
      String sourceType, String sourceRef, boolean customerVisible, Instant now) {
    this.tenantId = tenantId;
    this.journeyId = journeyId;
    this.milestoneCode = milestoneCode;
    this.milestoneLabel = milestoneCode.label();
    this.milestoneState = milestoneState;
    this.evidenceLevel = evidenceLevel;
    this.occurredAt = occurredAt;
    this.estimatedAt = estimatedAt;
    this.sourceType = sourceType;
    this.sourceRef = sourceRef;
    this.customerVisible = customerVisible;
    this.sortOrder = milestoneCode.sortOrder();
    this.createdAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getJourneyId() { return journeyId; }
  public MilestoneCode getMilestoneCode() { return milestoneCode; }
  public String getMilestoneLabel() { return milestoneLabel; }
  public MilestoneState getMilestoneState() { return milestoneState; }
  public EvidenceLevel getEvidenceLevel() { return evidenceLevel; }
  public Instant getOccurredAt() { return occurredAt; }
  public Instant getEstimatedAt() { return estimatedAt; }
  public String getSourceType() { return sourceType; }
  public String getSourceRef() { return sourceRef; }
  public boolean isCustomerVisible() { return customerVisible; }
  public int getSortOrder() { return sortOrder; }
  public Instant getCreatedAt() { return createdAt; }
}
