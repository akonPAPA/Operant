package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.AiWorkDtos.AiWorkSuggestionResponse;
import com.orderpilot.api.dto.AiWorkDtos.CreateAiWorkSuggestionRequest;
import com.orderpilot.api.dto.AiWorkDtos.CreateContextualAiWorkSuggestionRequest;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** OP-CAP-07A public AI Work DTO contract — no raw JSON or authority fields on requests/responses. */
class AiWorkResponseBoundaryTest {
  private static final Set<String> FORBIDDEN_RESPONSE_FIELDS = Set.of(
      "structuredPayloadJson",
      "evidenceRefsJson",
      "payloadJson",
      "rawPayload",
      "rawJson",
      "suggestionJson",
      "generatedText",
      "createdByUserId",
      "decidedByUserId",
      "actorId",
      "actorRole",
      "idempotencyKey",
      "auditCorrelationId");

  private static final Set<String> FORBIDDEN_REQUEST_FIELDS = Set.of("idempotencyKey");

  @Test
  void publicAiWorkResponseRecordHasNoForbiddenFields() {
    Set<String> names = Arrays.stream(AiWorkSuggestionResponse.class.getRecordComponents())
        .map(RecordComponent::getName)
        .collect(Collectors.toSet());
    assertThat(names).doesNotContainAnyElementsOf(FORBIDDEN_RESPONSE_FIELDS);
    assertThat(names).contains("summary", "displayFields", "nextActionCandidates", "evidence", "riskFlags");
  }

  @Test
  void createRequestBodiesDoNotAcceptIdempotencyKey() {
    assertThat(recordNames(CreateAiWorkSuggestionRequest.class)).doesNotContainAnyElementsOf(FORBIDDEN_REQUEST_FIELDS);
    assertThat(recordNames(CreateContextualAiWorkSuggestionRequest.class)).doesNotContainAnyElementsOf(FORBIDDEN_REQUEST_FIELDS);
  }

  private static Set<String> recordNames(Class<?> type) {
    return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
  }
}
