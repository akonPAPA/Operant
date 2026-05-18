package com.orderpilot.domain.validation;

import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name = "approval_requirement")
public class ApprovalRequirement {
  @Id @GeneratedValue private UUID id; @Column(name="tenant_id",nullable=false) private UUID tenantId; @Column(name="validation_run_id",nullable=false) private UUID validationRunId; @Column(name="extracted_line_item_id") private UUID extractedLineItemId; @Column(name="requirement_type",nullable=false) private String requirementType; @Column(nullable=false) private String severity; @Column(nullable=false) private String reason; @Column(nullable=false) private String status; @Column(name="created_at",nullable=false) private Instant createdAt; @Column(name="updated_at",nullable=false) private Instant updatedAt;
  protected ApprovalRequirement() {}
  public ApprovalRequirement(UUID tenantId, UUID validationRunId, UUID lineId, String requirementType, String severity, String reason, Instant now){this.tenantId=tenantId;this.validationRunId=validationRunId;this.extractedLineItemId=lineId;this.requirementType=requirementType;this.severity=severity;this.reason=reason;this.status="OPEN";this.createdAt=now;this.updatedAt=now;}
  public void setStatus(String status, Instant now){this.status=status;this.updatedAt=now;}
  public UUID getId(){return id;} public UUID getValidationRunId(){return validationRunId;} public UUID getExtractedLineItemId(){return extractedLineItemId;} public String getRequirementType(){return requirementType;} public String getSeverity(){return severity;} public String getReason(){return reason;} public String getStatus(){return status;} public Instant getCreatedAt(){return createdAt;}
}
