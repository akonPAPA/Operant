package com.orderpilot.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "quote_internal_order_boundary",
    uniqueConstraints = @UniqueConstraint(name = "uq_quote_internal_order_boundary_tenant_quote", columnNames = {"tenant_id", "draft_quote_id"}))
public class QuoteInternalOrderBoundary {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "draft_quote_id", nullable = false) private UUID draftQuoteId;
  @Column(nullable = false) private String status;
  @Column(name = "external_execution_status", nullable = false) private String externalExecutionStatus;
  @Column(name = "change_request_id") private UUID changeRequestId;
  @Column(name = "idempotency_key", nullable = false) private String idempotencyKey;
  @Column(name = "created_by") private UUID createdBy;
  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected QuoteInternalOrderBoundary() {}

  public QuoteInternalOrderBoundary(UUID tenantId, UUID draftQuoteId, String status, String externalExecutionStatus, UUID changeRequestId, String idempotencyKey, UUID createdBy, Instant createdAt) {
    this.tenantId = tenantId;
    this.draftQuoteId = draftQuoteId;
    this.status = status;
    this.externalExecutionStatus = externalExecutionStatus;
    this.changeRequestId = changeRequestId;
    this.idempotencyKey = idempotencyKey;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getDraftQuoteId() { return draftQuoteId; }
  public String getStatus() { return status; }
  public String getExternalExecutionStatus() { return externalExecutionStatus; }
  public UUID getChangeRequestId() { return changeRequestId; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public UUID getCreatedBy() { return createdBy; }
  public Instant getCreatedAt() { return createdAt; }
}
