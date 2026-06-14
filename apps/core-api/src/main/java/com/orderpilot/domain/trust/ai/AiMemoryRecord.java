package com.orderpilot.domain.trust.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance.
 *
 * A bounded, sanitized, tenant-scoped reusable AI/runtime knowledge item. Holds only typed, bounded
 * facts/signals ({@code title}/{@code summary}/{@code normalizedValue} are bounded VARCHAR, never TEXT)
 * plus governance metadata (authority, confidence, version, TTL, status, access counters). It NEVER
 * stores raw documents, OCR text, prompts, customer messages, secrets, card data, bank credentials, or
 * full PII. Memory is advisory and low-authority — it is never the source of truth for orders, quotes,
 * prices, stock, payments, counterparty trust, or approval status. Unique per
 * (tenant, namespace, memory key, version); only one {@code ACTIVE} version per key at a time.
 */
@Entity
@Table(name = "ai_memory_record",
    uniqueConstraints = @UniqueConstraint(name = "ux_ai_memory_record_tenant_ns_key_version",
        columnNames = {"tenant_id", "namespace", "memory_key", "version"}))
public class AiMemoryRecord {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Enumerated(EnumType.STRING)
  @Column(name = "namespace", nullable = false, length = 48) private AiMemoryNamespace namespace;

  @Column(name = "memory_key", nullable = false, length = 160) private String memoryKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "memory_type", nullable = false, length = 24) private AiMemoryType memoryType;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16) private AiMemoryStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "authority_level", nullable = false, length = 24) private AiMemoryAuthorityLevel authorityLevel;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 32) private AiMemorySourceType sourceType;

  @Column(name = "source_id") private UUID sourceId;

  /** Bounded, safe business reference pointer — never raw content. */
  @Column(name = "source_ref", length = 160) private String sourceRef;

  @Column(name = "title", nullable = false, length = 160) private String title;

  @Column(name = "summary", nullable = false, length = 512) private String summary;

  /** Bounded normalized/typed value (e.g. a canonical alias token) — never a raw payload. */
  @Column(name = "normalized_value", length = 256) private String normalizedValue;

  @Column(name = "confidence", nullable = false, precision = 5, scale = 4) private BigDecimal confidence;

  @Column(name = "weight", nullable = false) private int weight;

  @Column(name = "version", nullable = false) private int version;

  @Column(name = "expires_at") private Instant expiresAt;

  @Column(name = "invalidated_at") private Instant invalidatedAt;

  @Column(name = "invalidation_reason", length = 280) private String invalidationReason;

  @Column(name = "created_by") private UUID createdBy;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  @Column(name = "last_accessed_at") private Instant lastAccessedAt;

  @Column(name = "access_count", nullable = false) private long accessCount;

  protected AiMemoryRecord() {}

  public AiMemoryRecord(UUID tenantId, AiMemoryNamespace namespace, String memoryKey, AiMemoryType memoryType,
      AiMemoryAuthorityLevel authorityLevel, AiMemorySourceType sourceType, UUID sourceId, String sourceRef,
      String title, String summary, String normalizedValue, BigDecimal confidence, int weight, int version,
      Instant expiresAt, UUID createdBy, Instant now) {
    this.tenantId = tenantId;
    this.namespace = namespace;
    this.memoryKey = memoryKey;
    this.memoryType = memoryType;
    this.status = AiMemoryStatus.ACTIVE;
    this.authorityLevel = authorityLevel;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.sourceRef = sourceRef;
    this.title = title;
    this.summary = summary;
    this.normalizedValue = normalizedValue;
    this.confidence = confidence;
    this.weight = weight;
    this.version = version;
    this.expiresAt = expiresAt;
    this.createdBy = createdBy;
    this.createdAt = now;
    this.updatedAt = now;
    this.accessCount = 0;
  }

  /** Records one advisory read. Bounded — only the counter and timestamp move. */
  public void recordAccess(Instant now) {
    this.accessCount += 1;
    this.lastAccessedAt = now;
  }

  public void markInvalidated(String reason, Instant now) {
    this.status = AiMemoryStatus.INVALIDATED;
    this.invalidatedAt = now;
    this.invalidationReason = reason;
    this.updatedAt = now;
  }

  public void markExpired(Instant now) {
    this.status = AiMemoryStatus.EXPIRED;
    this.updatedAt = now;
  }

  public void markSuperseded(Instant now) {
    this.status = AiMemoryStatus.SUPERSEDED;
    this.updatedAt = now;
  }

  /** True when the record is past its TTL (regardless of whether it has been swept to EXPIRED yet). */
  public boolean isPastTtl(Instant now) {
    return expiresAt != null && !expiresAt.isAfter(now);
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public AiMemoryNamespace getNamespace() { return namespace; }
  public String getMemoryKey() { return memoryKey; }
  public AiMemoryType getMemoryType() { return memoryType; }
  public AiMemoryStatus getStatus() { return status; }
  public AiMemoryAuthorityLevel getAuthorityLevel() { return authorityLevel; }
  public AiMemorySourceType getSourceType() { return sourceType; }
  public UUID getSourceId() { return sourceId; }
  public String getSourceRef() { return sourceRef; }
  public String getTitle() { return title; }
  public String getSummary() { return summary; }
  public String getNormalizedValue() { return normalizedValue; }
  public BigDecimal getConfidence() { return confidence; }
  public int getWeight() { return weight; }
  public int getVersion() { return version; }
  public Instant getExpiresAt() { return expiresAt; }
  public Instant getInvalidatedAt() { return invalidatedAt; }
  public String getInvalidationReason() { return invalidationReason; }
  public UUID getCreatedBy() { return createdBy; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public Instant getLastAccessedAt() { return lastAccessedAt; }
  public long getAccessCount() { return accessCount; }
}
