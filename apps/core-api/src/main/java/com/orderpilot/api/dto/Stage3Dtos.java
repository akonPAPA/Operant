package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.UUID;

public final class Stage3Dtos {
  private Stage3Dtos() {}
  public record ApiDocumentUploadRequest(String sourceChannel, String documentType, String originalFilename, String contentType, String contentBase64, String receivedFrom, String subject, String rawMetadata) {}
  public record InboundDocumentResponse(UUID id, String sourceChannel, String documentType, String status, String originalFilename, String contentType, Long fileSizeBytes, String sha256Fingerprint, Instant receivedAt) {}
  public record MessageRequest(String channel, String externalMessageId, String conversationId, String senderHandle, String senderDisplayName, UUID customerAccountId, String direction, String messageType, String textContent, String rawPayload) {}
  public record ChannelMessageResponse(UUID id, String channel, String externalMessageId, String conversationId, String senderHandle, String messageType, String textContent, String status, Instant receivedAt) {}
  public record EmailWebhookRequest(String externalMessageId, String sender, String recipients, String subject, String bodyText, String rawPayload) {}
  public record WebhookPayloadRequest(String externalEventId, String rawPayload) {}
  public record ProcessingJobResponse(UUID id, String jobType, String targetType, UUID targetId, String status, Instant queuedAt) {}
  public record WebhookEventResponse(UUID id, String provider, String externalEventId, boolean signatureVerified, boolean replayDetected, String status, Instant receivedAt) {}
}