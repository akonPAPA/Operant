package com.orderpilot.domain.intake;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "object_storage_record")
public class ObjectStorageRecord {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "storage_provider", nullable = false) private String storageProvider;
  @Column(name = "bucket_name") private String bucketName;
  @Column(name = "object_key", nullable = false) private String objectKey;
  @Column(name = "original_filename") private String originalFilename;
  @Column(name = "content_type") private String contentType;
  @Column(name = "file_size_bytes") private Long fileSizeBytes;
  @Column(name = "sha256_fingerprint") private String sha256Fingerprint;
  @Column(nullable = false) private String status;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  protected ObjectStorageRecord() {}
  public ObjectStorageRecord(UUID tenantId, String objectKey, String originalFilename, String contentType, long fileSizeBytes, String sha256Fingerprint, Instant now) {
    this.tenantId = tenantId; this.storageProvider = "LOCAL_DEV"; this.objectKey = objectKey; this.originalFilename = originalFilename;
    this.contentType = contentType; this.fileSizeBytes = fileSizeBytes; this.sha256Fingerprint = sha256Fingerprint; this.status = "STORED";
    this.createdAt = now; this.updatedAt = now;
  }
  public UUID getId(){return id;} public String getObjectKey(){return objectKey;} public String getSha256Fingerprint(){return sha256Fingerprint;} public String getContentType(){return contentType;} public Long getFileSizeBytes(){return fileSizeBytes;}
}