package com.orderpilot.domain.trust;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-17A Document Trust Signal Foundation.
 *
 * Tenant-scoped fingerprint of an inbound document. Stores ONLY a deterministic SHA-256 hash of a
 * canonical metadata/hash input plus a bounded byte size — never raw document content or text.
 * Used for same-tenant duplicate-content detection.
 */
@Entity
@Table(name = "document_fingerprint")
public class DocumentFingerprint {
  @Id @GeneratedValue private UUID id;

  @Column(name = "tenant_id", nullable = false) private UUID tenantId;

  @Column(name = "source_document_id", nullable = false) private UUID sourceDocumentId;

  /** Hex-encoded SHA-256 of the canonical metadata/hash input. Never the raw content. */
  @Column(name = "content_sha256", nullable = false, length = 64) private String contentSha256;

  /** Optional bounded size of the source content in bytes. */
  @Column(name = "content_byte_size") private Long contentByteSize;

  @Column(name = "created_at", nullable = false) private Instant createdAt;

  protected DocumentFingerprint() {}

  public DocumentFingerprint(UUID tenantId, UUID sourceDocumentId, String contentSha256, Long contentByteSize, Instant createdAt) {
    this.tenantId = tenantId;
    this.sourceDocumentId = sourceDocumentId;
    this.contentSha256 = contentSha256;
    this.contentByteSize = contentByteSize;
    this.createdAt = createdAt;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getSourceDocumentId() { return sourceDocumentId; }
  public String getContentSha256() { return contentSha256; }
  public Long getContentByteSize() { return contentByteSize; }
  public Instant getCreatedAt() { return createdAt; }
}
