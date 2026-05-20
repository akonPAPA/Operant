package com.orderpilot.domain.workspace;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "quote_handoff_snapshot",
    uniqueConstraints = {
      @UniqueConstraint(name = "uq_quote_handoff_snapshot_tenant_idempotency", columnNames = {"tenant_id", "idempotency_key"}),
      @UniqueConstraint(name = "uq_quote_handoff_snapshot_tenant_quote_hash", columnNames = {"tenant_id", "draft_quote_id", "payload_hash"})
    })
public class QuoteHandoffSnapshot {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "draft_quote_id", nullable = false) private UUID draftQuoteId;
  @Column(nullable = false) private String status;
  @Column(name = "payload_version", nullable = false) private int payloadVersion;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "payload_json", columnDefinition = "jsonb", nullable = false) private String payloadJson;
  @Column(name = "payload_hash", nullable = false) private String payloadHash;
  @Column(name = "idempotency_key", nullable = false) private String idempotencyKey;
  @Column(name = "generated_by") private UUID generatedBy;
  @Column(name = "generated_at", nullable = false) private Instant generatedAt;

  protected QuoteHandoffSnapshot() {}

  public QuoteHandoffSnapshot(UUID tenantId, UUID draftQuoteId, String status, int payloadVersion, String payloadJson, String payloadHash, String idempotencyKey, UUID generatedBy, Instant generatedAt) {
    this.tenantId = tenantId;
    this.draftQuoteId = draftQuoteId;
    this.status = status;
    this.payloadVersion = payloadVersion;
    this.payloadJson = payloadJson;
    this.payloadHash = payloadHash;
    this.idempotencyKey = idempotencyKey;
    this.generatedBy = generatedBy;
    this.generatedAt = generatedAt;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getDraftQuoteId() { return draftQuoteId; }
  public String getStatus() { return status; }
  public int getPayloadVersion() { return payloadVersion; }
  public String getPayloadJson() { return payloadJson; }
  public String getPayloadHash() { return payloadHash; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public UUID getGeneratedBy() { return generatedBy; }
  public Instant getGeneratedAt() { return generatedAt; }
}
