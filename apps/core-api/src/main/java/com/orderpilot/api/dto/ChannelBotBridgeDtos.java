package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OP-CAP-06A Messenger Chatbot Integration Layer DTOs.
 *
 * <p>These responses surface the bridge between a verified {@code channel.ChannelConnection}
 * inbound event and the controlled bot runtime. They never expose secrets, raw bot tokens,
 * secret references, or raw provider payloads.
 */
public final class ChannelBotBridgeDtos {
  private ChannelBotBridgeDtos() {}

  /** Result of driving a verified inbound channel event into the controlled bot runtime. */
  public record ChannelBotBridgeResultResponse(
      UUID eventId,
      UUID channelConnectionId,
      String providerType,
      String externalEventId,
      String eventStatus,
      String bridgeStatus,
      UUID botConversationId,
      UUID botMessageId,
      String detectedIntent,
      boolean requiresHumanReview,
      UUID createdRfqDraftId,
      String message,
      Instant receivedAt,
      String verificationStatus,
      String externalExecution
  ) {}

  /**
   * Operator-facing bridge status summary. Combines the controlled safety contract (which flows the
   * bridge may run, which actions it can never perform, external execution state) with a bounded,
   * tenant-scoped recent-window count summary. All counts are derived from the same bounded recent
   * window that backs the events list, so this endpoint never triggers an unbounded scan.
   */
  public record ChannelBotBridgeStatusResponse(
      String externalExecution,
      int recentWindowLimit,
      int recentEventCount,
      int bridgedToBotCount,
      int pendingOrUnbridgedCount,
      List<String> supportedFlows,
      List<String> forbiddenActions
  ) {}

  /** Operator-facing list item linking a normalized channel event to its bot conversation. */
  public record ChannelBotBridgeEventResponse(
      UUID id,
      UUID channelConnectionId,
      String providerType,
      String externalEventId,
      String sourceActorType,
      String sourceActorExternalId,
      String normalizedText,
      String status,
      String verificationStatus,
      UUID botConversationId,
      UUID botMessageId,
      String botRuntimeStatus,
      Instant receivedAt,
      Instant processedAt
  ) {}
}
