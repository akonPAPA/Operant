package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-06B/06C controlled Bot Runtime RFQ Handoff DTOs.
 *
 * <p>These responses surface the reviewable internal RFQ handoff created from a verified channel/bot
 * message, plus the OP-CAP-06C operator-review workflow. They are operator views and never expose
 * secrets, raw bot tokens, secret references, or raw provider payloads. The handoff is a draft
 * request only — never a quote/order, and no action here mutates trusted business data or triggers
 * an external write.
 */
public final class ChannelRfqHandoffDtos {
  private ChannelRfqHandoffDtos() {}

  /** Stable operator view of a single RFQ handoff record, including workflow metadata. */
  public record ChannelRfqHandoffResponse(
      UUID id,
      UUID inboundChannelEventId,
      UUID channelConnectionId,
      String sourceChannel,
      String sourceExternalEventId,
      String sourceActorExternalId,
      UUID customerAccountId,
      UUID customerContactId,
      String requestText,
      String requestPreview,
      String detectedIntent,
      String status,
      UUID reviewerUserId,
      Instant reviewStartedAt,
      Instant dismissedAt,
      String dismissReason,
      Instant convertedAt,
      String conversionNote,
      Instant createdAt,
      Instant updatedAt) {}

  /** OP-CAP-06C: take a handoff into review. Reviewer id optional (no enforced auth principal yet). */
  public record StartReviewRfqHandoffRequest(UUID reviewerUserId) {}

  /** OP-CAP-06C: dismiss a handoff as invalid/irrelevant. {@code reason} must be non-blank. */
  public record DismissRfqHandoffRequest(String reason, UUID actorUserId) {}

  /** OP-CAP-06C: mark a handoff converted (workflow complete). No quote/order is created here. */
  public record MarkConvertedRfqHandoffRequest(String conversionNote, UUID actorUserId) {}
}
