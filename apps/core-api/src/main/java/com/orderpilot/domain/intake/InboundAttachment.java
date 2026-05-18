package com.orderpilot.domain.intake;
import jakarta.persistence.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="inbound_attachment")
public class InboundAttachment {
  @Id @GeneratedValue private UUID id; @Column(name="tenant_id",nullable=false) private UUID tenantId; @Column(name="channel_message_id") private UUID channelMessageId; @Column(name="inbound_document_id") private UUID inboundDocumentId; @Column(name="original_filename") private String originalFilename; @Column(name="content_type") private String contentType; @Column(name="file_size_bytes") private Long fileSizeBytes; @Column(name="object_storage_key") private String objectStorageKey; @Column(name="sha256_fingerprint") private String sha256Fingerprint; @Column(nullable=false) private String status; @Column(name="created_at",nullable=false) private Instant createdAt;
  protected InboundAttachment() {}
}