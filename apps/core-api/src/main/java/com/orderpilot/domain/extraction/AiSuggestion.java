package com.orderpilot.domain.extraction;
import jakarta.persistence.*; import java.math.BigDecimal; import java.time.Instant; import java.util.UUID; import org.hibernate.annotations.JdbcTypeCode; import org.hibernate.type.SqlTypes;
@Entity @Table(name="ai_suggestion")
public class AiSuggestion {
  @Id @GeneratedValue private UUID id; @Column(name="tenant_id",nullable=false) private UUID tenantId; @Column(name="extraction_run_id",nullable=false) private UUID extractionRunId; @Column(name="suggestion_type",nullable=false) private String suggestionType; @JdbcTypeCode(SqlTypes.JSON) @Column(name="suggestion_json",nullable=false,columnDefinition="jsonb") private String suggestionJson; @Column(nullable=false) private BigDecimal confidence; @Column(nullable=false) private String status; @Column(name="created_at",nullable=false) private Instant createdAt; @Column(name="updated_at",nullable=false) private Instant updatedAt;
  protected AiSuggestion() {}
  public AiSuggestion(UUID tenantId, UUID extractionRunId, String suggestionType, String suggestionJson, BigDecimal confidence, String status, Instant now){this.tenantId=tenantId; this.extractionRunId=extractionRunId; this.suggestionType=suggestionType; this.suggestionJson=suggestionJson; this.confidence=confidence; this.status=status; this.createdAt=now; this.updatedAt=now;}
  public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public UUID getExtractionRunId(){return extractionRunId;} public String getSuggestionType(){return suggestionType;} public String getSuggestionJson(){return suggestionJson;} public BigDecimal getConfidence(){return confidence;} public String getStatus(){return status;}
}
