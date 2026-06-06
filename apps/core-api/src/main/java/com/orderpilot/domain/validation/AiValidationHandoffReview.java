package com.orderpilot.domain.validation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-08B operator review record for one AI validation handoff.
 *
 * <p>One row per tenant and handoff. Holds review lifecycle state, latest operator decision, and
 * bounded correction summary fields. It never stores raw AI result JSON, raw document/message text,
 * or large line-item arrays. Recording a review mutates no master/business data and triggers no
 * connector or external write.
 */
@Entity
@Table(
    name = "ai_validation_handoff_review",
    uniqueConstraints = @UniqueConstraint(name = "uq_ai_handoff_review_tenant_handoff", columnNames = {"tenant_id", "handoff_id"}))
public class AiValidationHandoffReview {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "handoff_id", nullable = false) private UUID handoffId;
  @Column(name = "review_status", nullable = false) private String reviewStatus;
  @Column private String decision;
  @Column(name = "reason_code", length = 80) private String reasonCode;
  @Column(length = 500) private String note;
  @Column(name = "correction_summary", length = 500) private String correctionSummary;
  @Column(name = "corrected_intent", length = 120) private String correctedIntent;
  @Column(name = "corrected_customer_ref", length = 160) private String correctedCustomerRef;
  @Column(name = "corrected_line_count") private Integer correctedLineCount;
  @Column(name = "reviewed_by", length = 120) private String reviewedBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected AiValidationHandoffReview() {}

  public AiValidationHandoffReview(UUID tenantId, UUID handoffId, AiHandoffReviewStatus initialStatus, Instant now) {
    this.tenantId = tenantId;
    this.handoffId = handoffId;
    this.reviewStatus = initialStatus.name();
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void markStatus(AiHandoffReviewStatus status, String reviewedBy, Instant now) {
    this.reviewStatus = status.name();
    if (reviewedBy != null) {
      this.reviewedBy = reviewedBy;
    }
    this.updatedAt = now;
  }

  public void recordDecision(AiHandoffReviewStatus status, AiHandoffReviewDecision decision, String reasonCode,
      String note, String reviewedBy, Instant now) {
    this.reviewStatus = status.name();
    this.decision = decision.name();
    this.reasonCode = reasonCode;
    this.note = note;
    if (reviewedBy != null) {
      this.reviewedBy = reviewedBy;
    }
    this.updatedAt = now;
  }

  public void recordCorrection(String correctionSummary, String correctedIntent, String correctedCustomerRef,
      Integer correctedLineCount, Instant now) {
    this.correctionSummary = correctionSummary;
    this.correctedIntent = correctedIntent;
    this.correctedCustomerRef = correctedCustomerRef;
    this.correctedLineCount = correctedLineCount;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getHandoffId() { return handoffId; }
  public String getReviewStatus() { return reviewStatus; }
  public String getDecision() { return decision; }
  public String getReasonCode() { return reasonCode; }
  public String getNote() { return note; }
  public String getCorrectionSummary() { return correctionSummary; }
  public String getCorrectedIntent() { return correctedIntent; }
  public String getCorrectedCustomerRef() { return correctedCustomerRef; }
  public Integer getCorrectedLineCount() { return correctedLineCount; }
  public String getReviewedBy() { return reviewedBy; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
