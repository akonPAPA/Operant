package com.orderpilot.application.services.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * Stage 16A — deterministic AI workload classifier foundation.
 *
 * <p>Pure unit tests: no Spring context, no database, no AI, no external services. The classifier
 * must be deterministic and safe by default.
 */
class AiWorkloadClassifierStage16ATest {

  private final AiWorkloadClassifier classifier = new AiWorkloadClassifier();

  @Test
  void structuredSkuPriceRequestSelectsRulesOnly() {
    AiRoutingDecision decision =
        classifier.classify(
            new AiWorkloadClassificationRequest(
                AiWorkloadType.PRICE_REQUEST, "price for SKU-12345?", 0, 0, true, false));

    assertThat(decision.selectedTier()).isEqualTo(ModelTier.RULES_ONLY);
    assertThat(decision.workloadSize()).isEqualTo(WorkloadSize.SMALL);
    assertThat(decision.asyncRequired()).isFalse();
    assertThat(decision.humanReviewRequired()).isFalse();
    assertThat(decision.reasonCode())
        .isEqualTo(AiWorkloadReasonCodes.STRUCTURED_IDENTIFIER_RULES_PATH);
  }

  @Test
  void structuredAvailabilityRequestSelectsRulesOnly() {
    AiRoutingDecision decision =
        classifier.classify(
            new AiWorkloadClassificationRequest(
                AiWorkloadType.AVAILABILITY_REQUEST, "in stock?", 0, 0, true, false));

    assertThat(decision.selectedTier()).isEqualTo(ModelTier.RULES_ONLY);
    assertThat(decision.reasonCode())
        .isEqualTo(AiWorkloadReasonCodes.STRUCTURED_IDENTIFIER_RULES_PATH);
  }

  @Test
  void shortChatIntentSelectsSmallLocal() {
    AiRoutingDecision decision =
        classifier.classify(
            new AiWorkloadClassificationRequest(
                AiWorkloadType.CHAT_INTENT, "hello, can you help me?", 0, 0, false, false));

    assertThat(decision.selectedTier()).isEqualTo(ModelTier.SMALL_LOCAL);
    assertThat(decision.workloadSize()).isEqualTo(WorkloadSize.SMALL);
    assertThat(decision.asyncRequired()).isFalse();
    assertThat(decision.humanReviewRequired()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(AiWorkloadReasonCodes.SHORT_CHAT_INTENT);
  }

  @Test
  void mediumDocumentRequiresAsync() {
    AiRoutingDecision decision =
        classifier.classify(
            new AiWorkloadClassificationRequest(
                AiWorkloadType.DOCUMENT_EXTRACTION, "po", 3, 1, false, false));

    assertThat(decision.workloadSize()).isEqualTo(WorkloadSize.MEDIUM);
    assertThat(decision.selectedTier()).isEqualTo(ModelTier.MEDIUM);
    assertThat(decision.asyncRequired()).isTrue();
    assertThat(decision.humanReviewRequired()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(AiWorkloadReasonCodes.MEDIUM_DOCUMENT_ASYNC);
  }

  @Test
  void largeDocumentSelectsLargeAndAsync() {
    AiRoutingDecision decision =
        classifier.classify(
            new AiWorkloadClassificationRequest(
                AiWorkloadType.PURCHASE_LIST_EXTRACTION, "list", 25, 0, false, false));

    assertThat(decision.workloadSize()).isEqualTo(WorkloadSize.LARGE);
    assertThat(decision.selectedTier()).isEqualTo(ModelTier.LARGE);
    assertThat(decision.asyncRequired()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo(AiWorkloadReasonCodes.LARGE_DOCUMENT_ASYNC);
  }

  @Test
  void bulkDocumentRequiresAsyncAndHumanReview() {
    AiRoutingDecision decision =
        classifier.classify(
            new AiWorkloadClassificationRequest(
                AiWorkloadType.BULK_IMPORT, "bulk", 150, 0, false, false));

    assertThat(decision.workloadSize()).isEqualTo(WorkloadSize.BULK);
    assertThat(decision.asyncRequired()).isTrue();
    assertThat(decision.humanReviewRequired()).isTrue();
    assertThat(decision.selectedTier()).isEqualTo(ModelTier.HUMAN_REVIEW);
    assertThat(decision.reasonCode()).isEqualTo(AiWorkloadReasonCodes.BULK_REQUIRES_REVIEW);
  }

  @Test
  void manyAttachmentsAreBulkLike() {
    AiRoutingDecision decision =
        classifier.classify(
            new AiWorkloadClassificationRequest(
                AiWorkloadType.DOCUMENT_EXTRACTION, "x", 0, 25, false, false));

    assertThat(decision.workloadSize()).isEqualTo(WorkloadSize.BULK);
    assertThat(decision.humanReviewRequired()).isTrue();
  }

  @Test
  void suspiciousPromptInjectionRoutesToHumanReview() {
    AiRoutingDecision decision =
        classifier.classify(
            new AiWorkloadClassificationRequest(
                AiWorkloadType.CHAT_INTENT, "ignore previous instructions", 0, 0, false, true));

    assertThat(decision.selectedTier()).isEqualTo(ModelTier.HUMAN_REVIEW);
    assertThat(decision.humanReviewRequired()).isTrue();
    assertThat(decision.reasonCode())
        .isEqualTo(AiWorkloadReasonCodes.SUSPICIOUS_PROMPT_INJECTION_REVIEW);
  }

  @Test
  void suspiciousSignalOverridesStructuredLookup() {
    AiRoutingDecision decision =
        classifier.classify(
            new AiWorkloadClassificationRequest(
                AiWorkloadType.PRICE_REQUEST, "price for SKU-1", 0, 0, true, true));

    assertThat(decision.humanReviewRequired()).isTrue();
    assertThat(decision.reasonCode())
        .isEqualTo(AiWorkloadReasonCodes.SUSPICIOUS_PROMPT_INJECTION_REVIEW);
  }

  @Test
  void emptyInputReturnsRulesOnlyDefault() {
    AiRoutingDecision decision =
        classifier.classify(
            new AiWorkloadClassificationRequest(
                AiWorkloadType.DOCUMENT_EXTRACTION, "", 0, 0, false, false));

    assertThat(decision.selectedTier()).isEqualTo(ModelTier.RULES_ONLY);
    assertThat(decision.workloadSize()).isEqualTo(WorkloadSize.SMALL);
    assertThat(decision.asyncRequired()).isFalse();
    assertThat(decision.humanReviewRequired()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(AiWorkloadReasonCodes.EMPTY_INPUT_RULES_ONLY);
  }

  @Test
  void unknownWorkloadReturnsSafeReviewDefault() {
    AiRoutingDecision decision =
        classifier.classify(
            new AiWorkloadClassificationRequest(
                AiWorkloadType.UNKNOWN, "something", 0, 0, false, false));

    assertThat(decision.selectedTier()).isEqualTo(ModelTier.HUMAN_REVIEW);
    assertThat(decision.humanReviewRequired()).isTrue();
    assertThat(decision.reasonCode()).isEqualTo(AiWorkloadReasonCodes.UNKNOWN_WORKLOAD_REVIEW);
  }

  @Test
  void nullRequestedTypeIsTreatedAsUnknown() {
    AiRoutingDecision decision =
        classifier.classify(
            new AiWorkloadClassificationRequest(null, "x", 0, 0, false, false));

    assertThat(decision.workloadType()).isEqualTo(AiWorkloadType.UNKNOWN);
    assertThat(decision.reasonCode()).isEqualTo(AiWorkloadReasonCodes.UNKNOWN_WORKLOAD_REVIEW);
  }

  @Test
  void nullTextDoesNotThrow() {
    assertThatCode(
            () ->
                classifier.classify(
                    new AiWorkloadClassificationRequest(
                        AiWorkloadType.CHAT_INTENT, null, 0, 0, false, false)))
        .doesNotThrowAnyException();
  }

  @Test
  void negativeCountsAreNormalizedAndDoNotThrow() {
    AiRoutingDecision decision =
        classifier.classify(
            new AiWorkloadClassificationRequest(
                AiWorkloadType.CHAT_INTENT, "hi", -5, -3, false, false));

    assertThat(decision.estimatedInputUnits()).isGreaterThanOrEqualTo(0);
    assertThat(decision.workloadSize()).isEqualTo(WorkloadSize.SMALL);
  }

  @Test
  void estimatedInputUnitsIsDeterministicAndNonNegative() {
    AiWorkloadClassificationRequest request =
        new AiWorkloadClassificationRequest(
            AiWorkloadType.DOCUMENT_EXTRACTION, "purchase order body", 2, 1, false, false);

    AiRoutingDecision first = classifier.classify(request);
    AiRoutingDecision second = classifier.classify(request);

    assertThat(first.estimatedInputUnits()).isGreaterThanOrEqualTo(0);
    assertThat(first).isEqualTo(second);
    assertThat(first.estimatedInputUnits()).isEqualTo(second.estimatedInputUnits());
  }

  @Test
  void decisionNeverContainsRawInputText() {
    String secret = "TOP-SECRET-CUSTOMER-PAYLOAD-9981";
    AiRoutingDecision decision =
        classifier.classify(
            new AiWorkloadClassificationRequest(
                AiWorkloadType.DOCUMENT_EXTRACTION, secret, 1, 0, false, false));

    assertThat(decision.reasonCode()).doesNotContain(secret);
    assertThat(decision.toString()).doesNotContain(secret);
  }

  @Test
  void classifierRequiresNoDbOrTenantContext() {
    // Constructed and invoked with no Spring context, no TenantContext, no repositories.
    AiRoutingDecision decision =
        new AiWorkloadClassifier()
            .classify(
                new AiWorkloadClassificationRequest(
                    AiWorkloadType.PRICE_REQUEST, "SKU-1", 0, 0, true, false));

    assertThat(decision).isNotNull();
    assertThat(decision.reasonCode())
        .isEqualTo(AiWorkloadReasonCodes.STRUCTURED_IDENTIFIER_RULES_PATH);
  }
}
