package com.orderpilot.domain.workspace;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "quote_source_link")
public class QuoteSourceLink {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "quote_id", nullable = false) private UUID quoteId;
  @Column(name = "source_type", nullable = false) private String sourceType;
  @Column(name = "source_id", nullable = false) private UUID sourceId;
  @Column(name = "source_channel") private String sourceChannel;
  @Column(name = "source_external_ref") private String sourceExternalRef;
  @Column(name = "source_received_at") private Instant sourceReceivedAt;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "created_by") private UUID createdBy;
  @Column(name = "created_by_type", nullable = false) private String createdByType;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb") private String metadataJson;

  protected QuoteSourceLink() {}

  public QuoteSourceLink(UUID tenantId, UUID quoteId, String sourceType, UUID sourceId, String sourceChannel, String sourceExternalRef, Instant sourceReceivedAt, UUID createdBy, String createdByType, String metadataJson, Instant now) {
    this.tenantId = tenantId;
    this.quoteId = quoteId;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.sourceChannel = sourceChannel;
    this.sourceExternalRef = sourceExternalRef;
    this.sourceReceivedAt = sourceReceivedAt;
    this.createdBy = createdBy;
    this.createdByType = createdByType == null || createdByType.isBlank() ? "SYSTEM" : createdByType;
    this.metadataJson = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson;
    this.createdAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getQuoteId() { return quoteId; }
  public String getSourceType() { return sourceType; }
  public UUID getSourceId() { return sourceId; }
  public String getSourceChannel() { return sourceChannel; }
  public String getSourceExternalRef() { return sourceExternalRef; }
  public Instant getSourceReceivedAt() { return sourceReceivedAt; }
  public UUID getCreatedBy() { return createdBy; }
  public String getCreatedByType() { return createdByType; }
  public String getMetadataJson() { return metadataJson; }
}
