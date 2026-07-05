package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public, tenant-operator-safe Commerce Intelligence contracts.
 *
 * <p>These DTOs explain the existing RFQ demo flow without exposing tenant, actor, source-event,
 * audit, idempotency, provider, connector, or runtime-control internals.
 */
public final class CommerceIntelligenceDtos {
  private CommerceIntelligenceDtos() {}

  public record CommerceIntelligenceDemoFlowResponse(
      Instant generatedAt,
      String windowLabel,
      Summary summary,
      Safety safety,
      RuntimeControl runtimeControl,
      List<Bottleneck> bottlenecks,
      List<RecentFlow> recentFlows,
      List<NotProven> notProven) {}

  public record Summary(
      long rfqHandoffsTotal,
      long pendingReviewCount,
      long inReviewCount,
      long convertedCount,
      long dismissedCount,
      long aiAdvisorySuggestionsCount,
      long reviewRequiredDraftQuotesCount,
      long safeTerminalDemoDecisionsCount,
      long demoCompletedCount,
      long demoDeclinedCount) {}

  public record Safety(
      String externalExecutionStatus,
      String connectorCallStatus,
      String outboxStatus,
      Long observedConnectorCommandRows,
      Long observedChangeRequestRows,
      Long observedOutboxExternalExecutionRows,
      String measurementScope,
      String safetyStatement,
      List<String> notProven) {}

  public record RuntimeControl(
      boolean guarded,
      String demoRfqHandoffCreate,
      String rfqHandoffAiAdvisory,
      String draftQuoteCreate,
      String safeDemoDecision,
      String billingOrQuotaDimension,
      String denialTelemetry,
      String note) {}

  public record Bottleneck(
      String code, String label, long count, String severity, String explanation) {}

  /**
   * The handoff id is the opaque workflow handle already exposed by the RFQ operator workspace.
   * No internal source, storage, audit, actor, or idempotency identifiers are included.
   */
  public record RecentFlow(
      UUID handoffId,
      String sourceChannel,
      String requestPreview,
      String detectedIntent,
      String handoffStatus,
      String aiSuggestionStatus,
      String aiSchemaVersion,
      String aiRiskLevel,
      String draftQuoteStatus,
      String validationStatus,
      String safeTerminalState,
      List<String> blockingIssueCodes,
      Instant createdAt,
      Instant updatedAt) {}

  public record NotProven(String code, String label, String explanation) {}
}
