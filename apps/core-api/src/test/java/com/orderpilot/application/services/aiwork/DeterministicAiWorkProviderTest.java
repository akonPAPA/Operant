package com.orderpilot.application.services.aiwork;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.domain.aiwork.AiWorkSourceType;
import com.orderpilot.domain.aiwork.AiWorkSchemaVersion;
import com.orderpilot.domain.aiwork.AiWorkType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** OP-CAP-07A unit tests for the deterministic, provider-agnostic AI work generator. */
class DeterministicAiWorkProviderTest {
  private final DeterministicAiWorkProvider provider = new DeterministicAiWorkProvider();

  @Test
  void generatesDeterministicSummaryWithStableStrategyVersion() {
    var request = new AiWorkGenerationRequest(
        UUID.randomUUID(), AiWorkType.REQUEST_SUMMARY, AiWorkSourceType.CHANNEL_MESSAGE,
        UUID.randomUUID(), "Customer needs 10 brake pads for a Ford Transit");

    var first = provider.generate(request);
    var second = provider.generate(request);

    assertThat(first.generatedText()).isEqualTo(second.generatedText());
    assertThat(first.strategyVersion()).isEqualTo(DeterministicAiWorkProvider.STRATEGY_VERSION);
    assertThat(first.generatedText()).contains("Summary (advisory)");
    assertThat(first.structuredPayloadJson())
        .contains(AiWorkSchemaVersion.AI_WORK_SCHEMA_V1_REQUEST_SUMMARY.name());
  }

  @Test
  void customerReplyDraftIsFlaggedDraftOnlyAndMediumRisk() {
    var request = new AiWorkGenerationRequest(
        UUID.randomUUID(), AiWorkType.CUSTOMER_REPLY_DRAFT, AiWorkSourceType.OPERATOR_REVIEW,
        UUID.randomUUID(), "any");

    var result = provider.generate(request);

    assertThat(result.generatedText()).contains("Draft only");
    assertThat(result.riskLevel()).isEqualTo("MEDIUM");
    assertThat(result.structuredPayloadJson())
        .contains(AiWorkSchemaVersion.AI_WORK_SCHEMA_V1_CUSTOMER_REPLY_DRAFT.name());
    assertThat(result.structuredPayloadJson()).contains("\"containsCommitments\":false");
  }

  @Test
  void nextActionSuggestionAlwaysRequiresHumanApprovalAndEscalatesRiskForDiscount() {
    var request = new AiWorkGenerationRequest(
        UUID.randomUUID(), AiWorkType.NEXT_ACTION_SUGGESTION, AiWorkSourceType.QUOTE,
        UUID.randomUUID(), "Customer is asking for a discount on margin-sensitive items");

    var result = provider.generate(request);

    assertThat(result.structuredPayloadJson()).contains("\"requiresHumanApproval\":true");
    assertThat(result.structuredPayloadJson()).contains("\"actionType\"");
    assertThat(result.structuredPayloadJson())
        .contains(AiWorkSchemaVersion.AI_WORK_SCHEMA_V1_NEXT_ACTION_SUGGESTION.name());
    assertThat(result.structuredPayloadJson()).contains("REVIEW_MARGIN_DISCOUNT");
    assertThat(result.structuredPayloadJson()).doesNotContain("\"requiresHumanApproval\":false");
    assertThat(result.riskLevel()).isEqualTo("HIGH");
  }

  @Test
  void evidenceAlwaysAnchorsToSourceObject() {
    var request = new AiWorkGenerationRequest(
        UUID.randomUUID(), AiWorkType.SOURCE_CONTEXT_DIGEST, AiWorkSourceType.INBOUND_CHANNEL_EVENT,
        UUID.randomUUID(), "context");

    var result = provider.generate(request);

    assertThat(result.evidenceRefsJson()).contains("SOURCE_OBJECT");
    assertThat(result.structuredPayloadJson())
        .contains(AiWorkSchemaVersion.AI_WORK_SCHEMA_V1_REQUEST_SUMMARY.name());
  }
}
