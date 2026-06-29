package com.orderpilot.api.dto;

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

  /** Public cancellation intent. Actor identity is resolved from trusted request context. */
  public record LegacyChangeRequestCancelRequest(String reason) {}

  /** Internal service command. Never bind this type directly to a public request body. */
  public record ChangeRequestCancelCommand(UUID actorId, String reason) {}

  /**
   * Operator-safe handoff/change-request view. Exposes only business display state:
   * the quote handle, business lifecycle/readiness status, blocking issues, whether a snapshot
   * exists, the change-request handle the operator acts on, whether external execution is enabled
   * (always false on this disabled-by-design Stage 11E surface), and the allowed next actions.
   *
   * <p>Deliberately omits internal integrity/dedupe/source internals: payload hash, idempotency key,
   * raw snapshot id, raw payload JSON, generating actor id, and the lower-layer connector execution
   * status string. {@code changeRequestId} is retained as the public resource handle the operator
   * uses to approve-internal/cancel via {@code /api/v1/change-requests/{id}}.
   */
  public record QuoteHandoffResponse(
      UUID quoteId,
      String quoteLifecycleStatus,
      String handoffReadinessStatus,
      List<String> blockingIssues,
      boolean hasSnapshot,
      UUID changeRequestId,
      boolean externalExecutionEnabled,
      List<String> allowedActions) {}
}
