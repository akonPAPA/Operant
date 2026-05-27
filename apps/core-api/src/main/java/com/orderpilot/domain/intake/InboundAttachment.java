package com.orderpilot.domain.intake;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="inbound_attachment")
public class InboundAttachment {
  @Id @GeneratedValue private UUID id; @Column(name="tenant_id",nullable=false) private UUID tenantId; @Column(name="channel_message_id") private UUID channelMessageId; @Column(name="inbound_document_id") private UUID inboundDocumentId; @Column(name="original_filename") private String originalFilename; @Column(name="content_type") private String contentType; @Column(name="file_size_bytes") private Long fileSizeBytes; @Column(name="object_storage_key") private String objectStorageKey; @Column(name="sha256_fingerprint") private String sha256Fingerprint; @Column(nullable=false) private String status; @Column(name="created_at",nullable=false) private Instant createdAt;
  protected InboundAttachment() {}
  public InboundAttachment(UUID tenantId, UUID channelMessageId, UUID inboundDocumentId, String originalFilename, String contentType, Long fileSizeBytes, String objectStorageKey, String sha256Fingerprint, String status, Instant now) {
    this.tenantId = tenantId; this.channelMessageId = channelMessageId; this.inboundDocumentId = inboundDocumentId; this.originalFilename = originalFilename; this.contentType = contentType; this.fileSizeBytes = fileSizeBytes; this.objectStorageKey = objectStorageKey; this.sha256Fingerprint = sha256Fingerprint; this.status = status; this.createdAt = now;
  }
  public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public UUID getChannelMessageId(){return channelMessageId;} public UUID getInboundDocumentId(){return inboundDocumentId;} public String getOriginalFilename(){return originalFilename;} public String getContentType(){return contentType;} public Long getFileSizeBytes(){return fileSizeBytes;} public String getStatus(){return status;}
}
