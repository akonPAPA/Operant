package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.ChannelRfqHandoffDtos.ChannelRfqHandoffResponse;
import com.orderpilot.api.dto.Stage11EDtos.QuoteHandoffResponse;
import com.orderpilot.api.dto.Stage12ADtos.ApprovalDecision;
import com.orderpilot.api.dto.Stage12ADtos.QuoteApprovalCommandResponse;
import com.orderpilot.api.dto.Stage12ADtos.QuoteApprovalStateResponse;
import com.orderpilot.api.dto.Stage12ADtos.QuoteTransactionResponse;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Wave 01I Category D guard. The Stage 11E quote-handoff and channel RFQ-handoff response DTOs are
 * operator-safe: they must never expose internal integrity/dedupe internals, raw payloads, internal
 * actor ids, or raw source/correlation ids. This is a fast reflection check (no Spring context).
 */
class ResponseDtoLeakContractTest {
  private static final Set<String> FORBIDDEN_RESPONSE_FIELDS = Set.of(
      "payloadJson",
      "requestPayloadJson",
      "payloadHash",
      "idempotencyKey",
      "connectorIdempotencyKeyHash",
      "auditCorrelationId",
      "generatedBy",
      "createdBy",
      "createdByUserId",
      "reviewedBy",
      "decidedBy",
      "approvedBy",
      "actorId",
      "actorRole",
      "reviewerUserId",
      "secretRef",
      "secretValue",
      "secretReferenceId",
      "snapshotId",
      "executionStatus",
      "externalExecutionStatus",
      "sourceExternalEventId",
      "inboundChannelEventId",
      "channelConnectionId");

  @Test
  void quoteHandoffResponseExposesOnlySafeBusinessFields() {
    Set<String> names = componentNames(QuoteHandoffResponse.class);
    assertThat(names).doesNotContainAnyElementsOf(FORBIDDEN_RESPONSE_FIELDS);
    assertThat(names).contains(
        "quoteId", "handoffReadinessStatus", "hasSnapshot", "changeRequestId",
        "externalExecutionEnabled", "allowedActions");
  }

  @Test
  void stage11eDeclaresNoSnapshotResponseAndNoResponseRecordLeaks() {
    for (Class<?> nested : Stage11EDtos.class.getDeclaredClasses()) {
      // The unsafe (payloadJson/generatedBy/payloadHash) snapshot response DTO is removed for good.
      assertThat(nested.getSimpleName()).isNotEqualTo("QuoteHandoffSnapshotResponse");
      if (nested.isRecord() && nested.getSimpleName().endsWith("Response")) {
        assertThat(componentNames(nested))
            .as(nested.getSimpleName())
            .doesNotContainAnyElementsOf(FORBIDDEN_RESPONSE_FIELDS);
      }
    }
  }

  @Test
  void stage12aApprovalResponsesExposeOnlySafeBusinessFields() {
    assertThat(componentNames(QuoteTransactionResponse.class)).doesNotContainAnyElementsOf(FORBIDDEN_RESPONSE_FIELDS);

    Set<String> state = componentNames(QuoteApprovalStateResponse.class);
    assertThat(state).doesNotContainAnyElementsOf(FORBIDDEN_RESPONSE_FIELDS);
    assertThat(state).contains(
        "quoteId", "status", "approvalRequired", "changeRequestId", "externalExecutionEnabled");

    Set<String> command = componentNames(QuoteApprovalCommandResponse.class);
    assertThat(command).doesNotContainAnyElementsOf(FORBIDDEN_RESPONSE_FIELDS);
    assertThat(command).contains(
        "quoteId", "previousStatus", "newStatus", "approvalDecision", "externalExecutionEnabled");

    Set<String> decision = componentNames(ApprovalDecision.class);
    assertThat(decision).doesNotContainAnyElementsOf(FORBIDDEN_RESPONSE_FIELDS);
    assertThat(decision).contains("decision", "comment", "decidedAt", "previousQuoteStatus", "newQuoteStatus");
  }

  @Test
  void channelRfqHandoffResponseExposesOnlyOperatorSafeFields() {
    Set<String> names = componentNames(ChannelRfqHandoffResponse.class);
    assertThat(names).doesNotContainAnyElementsOf(FORBIDDEN_RESPONSE_FIELDS);
    assertThat(names).contains(
        "sourceChannel", "sourceActorExternalId", "requestPreview", "status", "detectedIntent");
  }

  private static Set<String> componentNames(Class<?> recordType) {
    return Arrays.stream(recordType.getRecordComponents())
        .map(RecordComponent::getName)
        .collect(Collectors.toSet());
  }
}
