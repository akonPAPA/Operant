package com.orderpilot.domain.pilot;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "shadow_run")
public class ShadowRun {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "source_type", nullable = false) private String sourceType;
  @Column(name = "source_id", nullable = false) private UUID sourceId;
  @Column(name = "prediction_type", nullable = false) private String predictionType;
  @Column(name = "provider_mode", nullable = false) private String providerMode;
  @Column(name = "provider_label", nullable = false) private String providerLabel;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "prediction_payload_json", columnDefinition = "jsonb", nullable = false) private String predictionPayloadJson;
  @Column(name = "confidence_score", precision = 5, scale = 4) private BigDecimal confidenceScore;
  @Column(nullable = false) private String status;
  // OP-CAP-11F pilot ROI readiness: structured (non-raw) pilot evidence fields. Nullable so
  // pre-existing Stage 10B shadow runs remain valid; never holds raw document/message/AI text.
  @Column(name = "exception_category") private String exceptionCategory;
  @Column(name = "manual_baseline_minutes", precision = 8, scale = 2) private BigDecimal manualBaselineMinutes;
  @Column(name = "assisted_processing_minutes", precision = 8, scale = 2) private BigDecimal assistedProcessingMinutes;
  @Column(name = "automation_candidate", nullable = false) private boolean automationCandidate;
  @Column(name = "review_required", nullable = false) private boolean reviewRequired;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "reviewed_at") private Instant reviewedAt;

  protected ShadowRun() {}

  public ShadowRun(UUID tenantId, String sourceType, UUID sourceId, String predictionType, String providerLabel, String predictionPayloadJson, BigDecimal confidenceScore, Instant now) {
    this(tenantId, sourceType, sourceId, predictionType, providerLabel, predictionPayloadJson, confidenceScore, null, null, null, false, false, now);
  }

  public ShadowRun(UUID tenantId, String sourceType, UUID sourceId, String predictionType, String providerLabel, String predictionPayloadJson, BigDecimal confidenceScore,
      String exceptionCategory, BigDecimal manualBaselineMinutes, BigDecimal assistedProcessingMinutes, boolean automationCandidate, boolean reviewRequired, Instant now) {
    this.tenantId = tenantId;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.predictionType = predictionType;
    this.providerMode = "MOCK_ONLY";
    this.providerLabel = providerLabel == null || providerLabel.isBlank() ? "orderpilot-mock-shadow-v1" : providerLabel;
    this.predictionPayloadJson = predictionPayloadJson == null || predictionPayloadJson.isBlank() ? "{}" : predictionPayloadJson;
    this.confidenceScore = confidenceScore;
    this.status = "RECORDED";
    this.exceptionCategory = exceptionCategory == null || exceptionCategory.isBlank() ? null : exceptionCategory;
    this.manualBaselineMinutes = manualBaselineMinutes;
    this.assistedProcessingMinutes = assistedProcessingMinutes;
    this.automationCandidate = automationCandidate;
    this.reviewRequired = reviewRequired;
    this.createdAt = now;
  }

  public void markReviewed(String status, Instant now) {
    this.status = status;
    this.reviewedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getSourceType() { return sourceType; }
  public UUID getSourceId() { return sourceId; }
  public String getPredictionType() { return predictionType; }
  public String getProviderMode() { return providerMode; }
  public String getProviderLabel() { return providerLabel; }
  public String getPredictionPayloadJson() { return predictionPayloadJson; }
  public BigDecimal getConfidenceScore() { return confidenceScore; }
  public String getStatus() { return status; }
  public String getExceptionCategory() { return exceptionCategory; }
  public BigDecimal getManualBaselineMinutes() { return manualBaselineMinutes; }
  public BigDecimal getAssistedProcessingMinutes() { return assistedProcessingMinutes; }
  public boolean isAutomationCandidate() { return automationCandidate; }
  public boolean isReviewRequired() { return reviewRequired; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getReviewedAt() { return reviewedAt; }
}
