package com.orderpilot.api.dto;

import com.orderpilot.domain.intake.ProcessingJob;
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
  // OP-CAP-28: safe operator/system-facing processing-job status contract. The original fields
  // (id/jobType/targetType/targetId/status/queuedAt — all tenant-owned, same-tenant values) are
  // preserved for the existing intake-jobs client. Added operational fields: safeMessage (business
  // language derived from status, NOT the raw lastError), retryable (backend-owned eligibility),
  // attempts (bounded count), and updatedAt. Never carries lastError/stack traces/provider payload/
  // prompt/extracted text/connector internals/cross-tenant ids.
  public record ProcessingJobResponse(UUID id, String jobType, String targetType, UUID targetId, String status, Instant queuedAt, String safeMessage, boolean retryable, int attempts, Instant updatedAt) {
    public static ProcessingJobResponse from(ProcessingJob job) {
      return new ProcessingJobResponse(job.getId(), job.getJobType(), job.getTargetType(), job.getTargetId(),
          job.getStatus(), job.getQueuedAt(), safeMessageFor(job.getStatus()), job.isRetryable(),
          job.getAttempts(), job.getUpdatedAt());
    }

    // Maps the internal status to safe business language. Failure detail (lastError) is deliberately
    // never echoed — only a generic, non-sensitive review message is surfaced.
    private static String safeMessageFor(String status) {
      if (status == null) { return "Processing status updated."; }
      return switch (status) {
        case "PENDING" -> "Pending processing.";
        case "PROCESSING" -> "Processing in progress.";
        case "SUCCEEDED" -> "Processing complete.";
        case "NEEDS_REVIEW" -> "Needs review.";
        case "FAILED" -> "Processing failed. Review required.";
        case "REJECTED" -> "Worker rejected result.";
        default -> "Processing status updated.";
      };
    }
  }
  public record WebhookEventResponse(UUID id, String provider, String externalEventId, boolean signatureVerified, boolean replayDetected, String status, Instant receivedAt) {}
  // OP-CAP-17E: raw object storage key is an internal, tenant-scoped path and must never reach the
  // frontend. Expose only a safe boolean indicator that a raw payload was persisted; the content
  // fingerprint (sha256) remains as the safe, non-locating document handle.
  public record InboundEventResponse(UUID id, String source, String externalEventId, String eventType, String fingerprintSha256, String status, boolean rawPayloadStored) {}
}
