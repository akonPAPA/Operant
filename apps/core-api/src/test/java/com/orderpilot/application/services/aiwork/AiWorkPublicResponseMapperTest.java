package com.orderpilot.application.services.aiwork;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orderpilot.api.dto.AiWorkDtos.AiWorkSuggestionResponse;
import com.orderpilot.domain.aiwork.AiWorkSchemaVersion;
import com.orderpilot.domain.aiwork.AiWorkSourceType;
import com.orderpilot.domain.aiwork.AiWorkSuggestion;
import com.orderpilot.domain.aiwork.AiWorkType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiWorkPublicResponseMapperTest {
  private static final Instant NOW = Instant.parse("2026-07-05T00:00:00Z");
  private static final String EVIDENCE =
      """
      [{"sourceType":"SOURCE_OBJECT","sourceLabel":"ignored provider label",
        "excerpt":"Backend-resolved request context.","confidence":0.8}]
      """;

  private final ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private final AiWorkPublicResponseMapper mapper = new AiWorkPublicResponseMapper(objectMapper);

  @Test
  void mapsAllFourKnownSchemaIdsToTypedPublicProjections() {
    List<SchemaCase> cases = List.of(
        new SchemaCase(
            AiWorkType.REQUEST_SUMMARY,
            AiWorkSchemaVersion.AI_WORK_SCHEMA_V1_REQUEST_SUMMARY,
            """
            {"schemaVersion":"AI_WORK_SCHEMA_V1_REQUEST_SUMMARY",
             "highlights":["Customer requests two brake pads."]}
            """),
        new SchemaCase(
            AiWorkType.NEXT_ACTION_SUGGESTION,
            AiWorkSchemaVersion.AI_WORK_SCHEMA_V1_NEXT_ACTION_SUGGESTION,
            """
            {"schemaVersion":"AI_WORK_SCHEMA_V1_NEXT_ACTION_SUGGESTION",
             "candidates":[{"actionType":"ROUTE_TO_REVIEW","label":"Route to review",
                            "requiresHumanApproval":false}]}
            """),
        new SchemaCase(
            AiWorkType.CUSTOMER_REPLY_DRAFT,
            AiWorkSchemaVersion.AI_WORK_SCHEMA_V1_CUSTOMER_REPLY_DRAFT,
            """
            {"schemaVersion":"AI_WORK_SCHEMA_V1_CUSTOMER_REPLY_DRAFT",
             "channelSafe":true,"containsCommitments":false}
            """),
        new SchemaCase(
            AiWorkType.VALIDATION_EXPLANATION,
            AiWorkSchemaVersion.AI_WORK_SCHEMA_V1_VALIDATION_EXPLANATION,
            """
            {"schemaVersion":"AI_WORK_SCHEMA_V1_VALIDATION_EXPLANATION",
             "explanationBasis":"VALIDATION_AND_SOURCE_CONTEXT"}
            """));

    for (SchemaCase schemaCase : cases) {
      AiWorkSuggestionResponse response =
          mapper.toResponse(suggestion(schemaCase.workType(), "Safe advisory summary.", schemaCase.payload(), EVIDENCE));

      assertThat(response.schemaVersion()).isEqualTo(schemaCase.schema().name());
      assertThat(response.summary()).isEqualTo("Safe advisory summary.");
      assertThat(response.safety().advisoryOnly()).isTrue();
      assertThat(response.safety().externalExecution()).isEqualTo("DISABLED");
      assertThat(response.safety().connectorCall()).isEqualTo("NOT_INVOKED");
      assertThat(response.safety().outbox()).isEqualTo("NOT_REQUESTED");
      assertThat(response.evidence()).hasSize(1);
    }

    AiWorkSuggestionResponse actionResponse = mapper.toResponse(
        suggestion(AiWorkType.NEXT_ACTION_SUGGESTION, "Safe action summary.", cases.get(1).payload(), EVIDENCE));
    assertThat(actionResponse.nextActionCandidates()).singleElement().satisfies(action -> {
      assertThat(action.actionType()).isEqualTo("ROUTE_TO_REVIEW");
      assertThat(action.requiresHumanApproval()).isTrue();
    });
    assertThat(actionResponse.safety().humanApprovalRequired()).isTrue();
  }

  @Test
  void ignoresUnknownProviderFieldsAndSerializesOnlyAllowlistedData() throws Exception {
    String payload =
        """
        {"schemaVersion":"AI_WORK_SCHEMA_V1_REQUEST_SUMMARY",
         "highlights":["Safe request summary."],
         "tenantId":"tenant-private","actorId":"actor-private",
         "idempotencyKey":"idem-private","rawPayload":{"unsafe":true},
         "payloadJson":"raw-json","prompt":"hidden prompt","token":"token-private",
         "apiKey":"key-private","secretReferenceId":"secret-ref-private"}
        """;

    String json = objectMapper.writeValueAsString(
        mapper.toResponse(suggestion(AiWorkType.REQUEST_SUMMARY, "Safe summary.", payload, EVIDENCE)));

    assertThat(json).contains("AI_WORK_SCHEMA_V1_REQUEST_SUMMARY", "Safe request summary.");
    assertThat(json).doesNotContain(
        "tenant-private",
        "actor-private",
        "idem-private",
        "rawPayload",
        "payloadJson",
        "hidden prompt",
        "token-private",
        "key-private",
        "secret-ref-private",
        "strategyVersion",
        "generatedText",
        "structuredPayloadJson",
        "evidenceRefsJson");
  }

  @Test
  void malformedOrWrongSchemaPayloadFailsClosedWithoutRawFallback() throws Exception {
    AiWorkSuggestionResponse malformed = mapper.toResponse(
        suggestion(
            AiWorkType.REQUEST_SUMMARY,
            "provider returned invalid JSON: {raw-private-value",
            "{not-json",
            EVIDENCE));
    AiWorkSuggestionResponse wrongSchema = mapper.toResponse(
        suggestion(
            AiWorkType.REQUEST_SUMMARY,
            "provider raw mismatch value",
            """
            {"schemaVersion":"AI_WORK_SCHEMA_V1_VALIDATION_EXPLANATION",
             "explanationBasis":"mismatch-private-value"}
            """,
            EVIDENCE));

    assertThat(malformed.summary()).isEqualTo(AiWorkPublicResponseMapper.SAFE_FAILURE_MESSAGE);
    assertThat(malformed.displayFields()).isEmpty();
    assertThat(malformed.evidence()).isEmpty();
    assertThat(wrongSchema.summary()).isEqualTo(AiWorkPublicResponseMapper.SAFE_FAILURE_MESSAGE);
    String json = objectMapper.writeValueAsString(List.of(malformed, wrongSchema));
    assertThat(json).doesNotContain(
        "raw-private-value", "mismatch-private-value", "provider raw mismatch value");
  }

  @Test
  void redactsSecretLikeTextAndBoundsDisplayValues() throws Exception {
    String longValue = "A".repeat(400);
    String payload =
        """
        {"schemaVersion":"AI_WORK_SCHEMA_V1_REQUEST_SUMMARY",
         "highlights":["apiKey=private-key","%s","Safe bounded field"]}
        """.formatted(longValue);
    String evidence =
        """
        [{"sourceType":"SOURCE_OBJECT","excerpt":"Authorization: Bearer private-token"}]
        """;

    AiWorkSuggestionResponse response = mapper.toResponse(
        suggestion(
            AiWorkType.REQUEST_SUMMARY,
            "password=private-password",
            payload,
            evidence));
    String json = objectMapper.writeValueAsString(response);

    assertThat(response.summary()).isEqualTo(AiWorkPublicResponseMapper.SAFE_FAILURE_MESSAGE);
    assertThat(response.displayFields()).hasSize(2);
    assertThat(response.displayFields().get(0).value()).hasSize(257).endsWith("\u2026");
    assertThat(response.evidence()).singleElement().satisfies(item ->
        assertThat(item.excerpt()).isNull());
    assertThat(json).doesNotContain(
        "private-key", "private-token", "private-password", "Authorization", "Bearer");
  }

  @Test
  void normalizesUntrustedRiskFailHighAndDropsInvalidConfidence() throws Exception {
    AiWorkSuggestion unsafeRisk = new AiWorkSuggestion(
        UUID.randomUUID(),
        AiWorkType.REQUEST_SUMMARY,
        AiWorkSourceType.RFQ_HANDOFF,
        UUID.randomUUID(),
        "provider-internal-version",
        "apiKey=private-risk",
        new BigDecimal("4.20"),
        "Safe summary.",
        """
        {"schemaVersion":"AI_WORK_SCHEMA_V1_REQUEST_SUMMARY","highlights":["Safe field"]}
        """,
        EVIDENCE,
        null,
        UUID.randomUUID(),
        NOW);

    AiWorkSuggestionResponse response = mapper.toResponse(unsafeRisk);
    String json = objectMapper.writeValueAsString(response);

    assertThat(response.riskLevel()).isEqualTo("HIGH");
    assertThat(response.confidence()).isNull();
    assertThat(response.safety().humanApprovalRequired()).isTrue();
    assertThat(json).doesNotContain("private-risk", "4.20");
  }

  private static AiWorkSuggestion suggestion(
      AiWorkType workType, String summary, String payload, String evidence) {
    return new AiWorkSuggestion(
        UUID.randomUUID(),
        workType,
        AiWorkSourceType.RFQ_HANDOFF,
        UUID.randomUUID(),
        "provider-internal-version",
        workType == AiWorkType.NEXT_ACTION_SUGGESTION ? "MEDIUM" : "LOW",
        new BigDecimal("0.75"),
        summary,
        payload,
        evidence,
        "internal-idempotency-key",
        UUID.randomUUID(),
        NOW);
  }

  private record SchemaCase(
      AiWorkType workType, AiWorkSchemaVersion schema, String payload) {}
}
