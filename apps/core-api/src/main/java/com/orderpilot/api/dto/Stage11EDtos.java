package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Stage11EDtos {
  private Stage11EDtos() {}

  /** Public handoff intent; actor and role are resolved from trusted runtime context. */
  public record LegacyQuoteHandoffRequest(String reason) {}

  public record QuoteHandoffCommand(UUID actorId, String actorRole, String reason) {}

  /** Public ChangeRequest-draft intent. Actor is backend-owned. */
  public record LegacyChangeRequestDraftRequest(
      String targetSystemType,
      String targetEntityType,
      String requestedAction) {}

  public record ChangeRequestDraftCommand(
      UUID actorId,
      String targetSystemType,
      String targetEntityType,
      String requestedAction) {}

  public record ChangeRequestCancelCommand(UUID actorId, String reason) {}

  public record QuoteHandoffResponse(
      UUID quoteId,
      String quoteLifecycleStatus,
      String handoffReadinessStatus,
      List<String> blockingIssues,
      UUID snapshotId,
      UUID changeRequestId,
      String payloadHash,
      String idempotencyKey,
      String executionStatus,
      List<String> allowedActions) {}

  public record QuoteHandoffSnapshotResponse(
      UUID id,
      UUID quoteId,
      String status,
      int payloadVersion,
      String payloadHash,
      String idempotencyKey,
      Instant generatedAt,
      UUID generatedBy,
      String payloadJson) {}
}
