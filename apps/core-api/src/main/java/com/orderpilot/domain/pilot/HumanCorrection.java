package com.orderpilot.domain.pilot;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "human_correction")
public class HumanCorrection {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "shadow_run_id", nullable = false) private UUID shadowRunId;
  @Column(name = "corrected_by_user_id") private UUID correctedByUserId;
  @Column(name = "correction_type", nullable = false) private String correctionType;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "before_payload_json", columnDefinition = "jsonb", nullable = false) private String beforePayloadJson;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "after_payload_json", columnDefinition = "jsonb", nullable = false) private String afterPayloadJson;
  @Column(name = "correction_reason") private String correctionReason;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected HumanCorrection() {}

  public HumanCorrection(UUID tenantId, UUID shadowRunId, UUID correctedByUserId, String correctionType, String beforePayloadJson, String afterPayloadJson, String correctionReason, Instant now) {
    this.tenantId = tenantId;
    this.shadowRunId = shadowRunId;
    this.correctedByUserId = correctedByUserId;
    this.correctionType = correctionType;
    this.beforePayloadJson = beforePayloadJson == null || beforePayloadJson.isBlank() ? "{}" : beforePayloadJson;
    this.afterPayloadJson = afterPayloadJson == null || afterPayloadJson.isBlank() ? "{}" : afterPayloadJson;
    this.correctionReason = correctionReason;
    this.createdAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getShadowRunId() { return shadowRunId; }
  public UUID getCorrectedByUserId() { return correctedByUserId; }
  public String getCorrectionType() { return correctionType; }
  public String getBeforePayloadJson() { return beforePayloadJson; }
  public String getAfterPayloadJson() { return afterPayloadJson; }
  public String getCorrectionReason() { return correctionReason; }
  public Instant getCreatedAt() { return createdAt; }
}
