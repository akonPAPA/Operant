package com.orderpilot.domain.intake;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "inbound_document")
public class InboundDocument {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "source_channel", nullable = false) private String sourceChannel;
  @Column(name = "document_type", nullable = false) private String documentType;
  @Column(nullable = false) private String status;
  @Column(name = "original_filename") private String originalFilename;
  @Column(name = "content_type") private String contentType;
  @Column(name = "file_size_bytes") private Long fileSizeBytes;
  @Column(name = "object_storage_key") private String objectStorageKey;
  @Column(name = "sha256_fingerprint") private String sha256Fingerprint;
  @Column(name = "received_from") private String receivedFrom;
  private String subject;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "raw_metadata", nullable = false, columnDefinition = "jsonb") private String rawMetadata;
  @Column(name = "received_at", nullable = false) private Instant receivedAt;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  protected InboundDocument() {}
  public InboundDocument(UUID tenantId, String sourceChannel, String documentType, String status, String originalFilename, String contentType, Long fileSizeBytes, String objectStorageKey, String sha256Fingerprint, String receivedFrom, String subject, String rawMetadata, Instant now) {
    this.tenantId=tenantId; this.sourceChannel=sourceChannel; this.documentType=documentType; this.status=status; this.originalFilename=originalFilename; this.contentType=contentType; this.fileSizeBytes=fileSizeBytes; this.objectStorageKey=objectStorageKey; this.sha256Fingerprint=sha256Fingerprint; this.receivedFrom=receivedFrom; this.subject=subject; this.rawMetadata=rawMetadata == null || rawMetadata.isBlank() ? "{}" : rawMetadata; this.receivedAt=now; this.createdAt=now; this.updatedAt=now;
  }
  public void markDuplicate(Instant now){this.status="DUPLICATE"; this.updatedAt=now;} public void markQueued(Instant now){this.status="QUEUED"; this.updatedAt=now;}
  public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public String getSourceChannel(){return sourceChannel;} public String getDocumentType(){return documentType;} public String getStatus(){return status;} public String getOriginalFilename(){return originalFilename;} public String getContentType(){return contentType;} public Long getFileSizeBytes(){return fileSizeBytes;} public String getObjectStorageKey(){return objectStorageKey;} public String getSha256Fingerprint(){return sha256Fingerprint;} public Instant getReceivedAt(){return receivedAt;}
}