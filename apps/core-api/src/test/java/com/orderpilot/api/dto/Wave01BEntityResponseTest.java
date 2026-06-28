package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage5Dtos.*;
import com.orderpilot.api.dto.Stage6Dtos.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Wave01BEntityResponseTest {
  private static final String[] FORBIDDEN_JSON_FIELDS = {
      "\"tenantId\"", "\"actorId\"", "\"userId\"", "\"assignedToUserId\"", "\"decidedBy\"",
      "\"sourceId\"", "\"extractionResultId\"", "\"validationIssueId\"", "\"detailsJson\"",
      "\"suggestionJson\"", "\"resultJson\"", "\"payloadJson\"", "\"idempotencyKey\"",
      "\"auditCorrelationId\"", "\"errorMessage\""
  };

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void workspaceResponsesSerializeWithoutTenantActorSourceOrRawJsonFields() throws Exception {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    List<Object> responses = List.of(
        new ExceptionCaseDto(UUID.randomUUID(), "CASE-1", "Review", "OPEN", "HIGH", "ERROR", "Summary", now, null),
        new ExceptionCaseIssueDto(UUID.randomUUID(), "PRODUCT", "ERROR", "OPEN", "Product requires review"),
        new SuggestedFixDto(UUID.randomUUID(), "MAP_PRODUCT", "SUGGESTED", new BigDecimal("0.90"), "Candidate found"),
        new ApprovalDecisionDto(UUID.randomUUID(), "EXCEPTION_CASE", UUID.randomUUID(), "APPROVE", "Reviewed", now));

    for (Object response : responses) {
      assertSafeJson(response);
    }
  }

  @Test
  void extractionValidationResponseSerializesOnlyMappedBusinessFields() throws Exception {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    ValidationIssueResponse issue = new ValidationIssueResponse(
        UUID.randomUUID(), UUID.randomUUID(), "PRODUCT_NOT_FOUND", "ERROR", "Product not found", "OPEN", now);
    ApprovalRequirementResponse approval = new ApprovalRequirementResponse(
        UUID.randomUUID(), UUID.randomUUID(), "MARGIN_REVIEW", "WARNING", "Review required", "OPEN", now);
    SubstituteCandidateResponse substitute = new SubstituteCandidateResponse(
        UUID.randomUUID(), UUID.randomUUID(), "EQUIVALENT", "LOW", new BigDecimal("0.80"),
        "Equivalent item", "IN_STOCK", "PASS", false, "SUGGESTED");
    LineItemValidationResponse line = new LineItemValidationResponse(
        1, "SKU-1", "Item", "2", "EA",
        new ProductMatchCandidateResponse(UUID.randomUUID(), "EXACT", new BigDecimal("0.99"), "MATCHED"),
        "EA", "IN_STOCK", "PASS", "PASS", List.of(substitute), List.of(issue), List.of(approval),
        "NEEDS_OPERATOR_REVIEW");
    ExtractionValidationResponse response = new ExtractionValidationResponse(
        UUID.randomUUID(), "COMPLETED", "WARNING", "NEEDS_OPERATOR_REVIEW",
        List.of(line), List.of(issue), List.of(approval));

    String json = assertSafeJson(response);
    assertThat(json).contains("\"routingRecommendation\":\"NEEDS_OPERATOR_REVIEW\"");
    assertThat(json).doesNotContain("detailsJson", "resultJson");
  }

  private String assertSafeJson(Object response) throws Exception {
    String json = objectMapper.writeValueAsString(response);
    assertThat(json).doesNotContain(FORBIDDEN_JSON_FIELDS);
    return json;
  }
}
