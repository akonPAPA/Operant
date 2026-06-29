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

  /**
   * Operator-safe view of a single RFQ handoff record plus workflow metadata.
   *
   * <p>Exposes only what the operator review screen needs: the handoff handle, the source channel and
   * the channel sender identifier, the linked tenant customer/contact handles, the request text/preview,
   * detected intent, status, and workflow timestamps/notes. It deliberately omits internal actor and
   * raw source/correlation identifiers: the reviewing Operant user id, the inbound channel event id,
   * the channel connection id, and the raw external provider event id.
   */
  public record ChannelRfqHandoffResponse(
      UUID id,
      String sourceChannel,
      String sourceActorExternalId,
      UUID customerAccountId,
      UUID customerContactId,
      String requestText,
      String requestPreview,
      String detectedIntent,
      String status,
      Instant reviewStartedAt,
      Instant dismissedAt,
      String dismissReason,
      Instant convertedAt,
      String conversionNote,
      Instant createdAt,
      Instant updatedAt) {}

  /**
   * OP-CAP-06C: dismiss a handoff as invalid/irrelevant. {@code reason} must be non-blank.
   *
   * <p>Business intent only. The acting reviewer is resolved from the trusted request context
   * ({@link com.orderpilot.security.RequestActorResolver}) — never from the request body.
   */
  public record DismissRfqHandoffRequest(String reason) {}

  /**
   * OP-CAP-06C: mark a handoff converted (workflow complete). No quote/order is created here.
   *
   * <p>Business intent only. The acting reviewer is resolved from the trusted request context
   * ({@link com.orderpilot.security.RequestActorResolver}) — never from the request body.
   */
  public record MarkConvertedRfqHandoffRequest(String conversionNote) {}
}
