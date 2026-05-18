package com.orderpilot.domain.workspace;

import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="approval_decision")
public class ApprovalDecision {
  @Id @GeneratedValue private UUID id; @Column(name="tenant_id",nullable=false) private UUID tenantId; @Column(name="target_type",nullable=false) private String targetType; @Column(name="target_id",nullable=false) private UUID targetId; @Column(nullable=false) private String decision; private String reason; @Column(name="decided_by") private UUID decidedBy; @Column(name="decided_at",nullable=false) private Instant decidedAt; @Column(name="created_at",nullable=false) private Instant createdAt;
  protected ApprovalDecision() {}
  public ApprovalDecision(UUID tenantId, String targetType, UUID targetId, String decision, String reason, UUID decidedBy, Instant now){this.tenantId=tenantId;this.targetType=targetType;this.targetId=targetId;this.decision=decision;this.reason=reason;this.decidedBy=decidedBy;this.decidedAt=now;this.createdAt=now;}
  public UUID getId(){return id;} public String getTargetType(){return targetType;} public UUID getTargetId(){return targetId;} public String getDecision(){return decision;} public String getReason(){return reason;} public UUID getDecidedBy(){return decidedBy;} public Instant getDecidedAt(){return decidedAt;}
}
