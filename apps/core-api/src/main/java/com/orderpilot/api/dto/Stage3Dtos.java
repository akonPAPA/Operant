package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Stage3Dtos {
  private Stage3Dtos() {}
  public record ApiDocumentUploadRequest(String sourceChannel, String documentType, String originalFilename, String contentType, String contentBase64, String receivedFrom, String subject, String rawMetadata) {}
  public record ApiUploadRequest(String source, String customerHint, String messageText, String externalReference) {}
  public record InboundDocumentResponse(UUID id, String sourceChannel, String documentType, String status, String originalFilename, String contentType, Long fileSizeBytes, String sha256Fingerprint, Instant receivedAt) {}
  public record MessageRequest(String channel, String externalMessageId, String conversationId, String senderHandle, String senderDisplayName, UUID customerAccountId, String direction, String messageType, String textContent, String rawPayload) {}
  public record ChannelMessageResponse(UUID id, String channel, String externalMessageId, String conversationId, String senderHandle, String messageType, String textContent, String status, Instant receivedAt) {}
  public record AttachmentMetadataRequest(String originalFilename, String contentType, Long sizeBytes, String objectStorageKey, String fingerprintSha256) {}
  public record EmailWebhookRequest(String externalMessageId, String sender, String recipients, String subject, String bodyText, String rawPayload, List<AttachmentMetadataRequest> attachments) {}
  public record WebhookPayloadRequest(String externalEventId, String rawPayload) {}
  public record ProcessingJobResponse(UUID id, String jobType, String targetType, UUID targetId, String status, Instant queuedAt) {}
  public record WebhookEventResponse(UUID id, String provider, String externalEventId, boolean signatureVerified, boolean replayDetected, String status, Instant receivedAt) {}
  // OP-CAP-17E: raw object storage key is an internal, tenant-scoped path and must never reach the
  // frontend. Expose only a safe boolean indicator that a raw payload was persisted; the content
  // fingerprint (sha256) remains as the safe, non-locating document handle.
  public record InboundEventResponse(UUID id, String source, String externalEventId, String eventType, String fingerprintSha256, String status, boolean rawPayloadStored) {}
}
