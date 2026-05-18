package com.orderpilot.domain.validation;

import jakarta.persistence.*; import java.math.BigDecimal; import java.time.Instant; import java.util.UUID;
@Entity @Table(name = "uom_normalization_result")
public class UomNormalizationResult {
  @Id @GeneratedValue private UUID id; @Column(name="tenant_id",nullable=false) private UUID tenantId; @Column(name="validation_run_id",nullable=false) private UUID validationRunId; @Column(name="extracted_line_item_id",nullable=false) private UUID extractedLineItemId; @Column(name="raw_uom") private String rawUom; @Column(name="normalized_uom") private String normalizedUom; @Column(nullable=false) private String status; @Column(nullable=false) private BigDecimal confidence; @Column(name="created_at",nullable=false) private Instant createdAt; @Column(name="updated_at",nullable=false) private Instant updatedAt;
  protected UomNormalizationResult() {}
  public UomNormalizationResult(UUID tenantId, UUID validationRunId, UUID lineId, String rawUom, String normalizedUom, String status, BigDecimal confidence, Instant now){this.tenantId=tenantId;this.validationRunId=validationRunId;this.extractedLineItemId=lineId;this.rawUom=rawUom;this.normalizedUom=normalizedUom;this.status=status;this.confidence=confidence;this.createdAt=now;this.updatedAt=now;}
  public UUID getId(){return id;} public UUID getExtractedLineItemId(){return extractedLineItemId;} public String getRawUom(){return rawUom;} public String getNormalizedUom(){return normalizedUom;} public String getStatus(){return status;} public BigDecimal getConfidence(){return confidence;}
}
