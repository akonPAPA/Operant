package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.Stage6Dtos.ApprovalDecisionRequest;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Wave 01A — Stage6/workspace request contract proof.
 * The public request DTO carries business intent only; backend-owned authority fields
 * (createdBy, decidedBy, actorId, tenantId, status, approvedBy, etc.) are never
 * accepted from the client body.
 */
class Stage6AuthorityRequestContractTest {
  private static final Set<String> FORBIDDEN = Set.of(
      "createdby", "reviewedby", "actorrole", "actoruserid", "decidedby",
      "status", "approvedby", "actorid", "tenantid", "userid", "actor",
      "auditcorrelationid", "idempotencykey", "sourcevalidationrunid",
      "sourceexceptioncaseid", "sourceextractionresultid");

  private static final Set<String> ALLOWED = Set.of(
      "targettype", "targetid", "decision", "reason");

  @Test
  void approvalDecisionRequestCarriesBusinessIntentOnly() {
    RecordComponent[] components = ApprovalDecisionRequest.class.getRecordComponents();
    assertThat(components).as("ApprovalDecisionRequest must have business intent fields only").isNotEmpty();

    for (RecordComponent c : components) {
      String name = c.getName().toLowerCase(Locale.ROOT);
      assertThat(FORBIDDEN)
          .as("ApprovalDecisionRequest.%s must not be a client-owned authority field", c.getName())
          .doesNotContain(name);
    }
  }

  @Test
  void approvalDecisionRequestHasExpectedBusinessFields() {
    Set<String> names = java.util.Arrays.stream(ApprovalDecisionRequest.class.getRecordComponents())
        .map(c -> c.getName().toLowerCase(Locale.ROOT))
        .collect(java.util.stream.Collectors.toSet());

    for (String allowed : ALLOWED) {
      assertThat(names)
          .as("ApprovalDecisionRequest must contain business field '%s'", allowed)
          .contains(allowed);
    }
  }

  @Test
  void approvalDecisionRequestHasExactlyFourComponents() {
    assertThat(ApprovalDecisionRequest.class.getRecordComponents())
        .as("ApprovalDecisionRequest should have exactly 4 business intent fields after decidedBy removal")
        .hasSize(4);
  }

  // Wave 01H Category C: every public Stage6 request record carries business intent only — none may
  // accept a client-owned actor/authority or workflow-state field. The acting user, idempotency and
  // status are backend-owned and resolved server-side (RequestActorResolver / trusted context).
  private static final Set<String> FORBIDDEN_AUTHORITY = Set.of(
      "actoruserid", "actorid", "actorrole", "userid", "createdby", "createdbyuserid",
      "correctedbyuserid", "reviewedby", "decidedby", "approvedby",
      "approvalstatus", "executionstatus", "tenantid", "idempotencykey");

  @Test
  void allStage6PublicRequestRecordsCarryBusinessIntentOnly() {
    List<Class<?>> requestRecords = List.of(
        Stage6Dtos.AssignRequest.class,
        Stage6Dtos.StatusRequest.class,
        Stage6Dtos.ApprovalDecisionRequest.class,
        Stage6Dtos.NoteRequest.class,
        Stage6Dtos.ReviewActionRequest.class,
        Stage6Dtos.ReviewNoteRequest.class,
        Stage6Dtos.ConfirmCandidateRequest.class,
        Stage6Dtos.CorrectUomRequest.class,
        Stage6Dtos.CorrectQuantityRequest.class,
        Stage6Dtos.MapProductRequest.class,
        Stage6Dtos.SubstituteDecisionRequest.class,
        Stage6Dtos.IssueDispositionRequest.class,
        Stage6Dtos.DraftLineCorrectionRequest.class);

    for (Class<?> requestRecord : requestRecords) {
      for (RecordComponent component : requestRecord.getRecordComponents()) {
        assertThat(FORBIDDEN_AUTHORITY)
            .as("%s.%s must not be a client-owned authority/state field", requestRecord.getSimpleName(), component.getName())
            .doesNotContain(component.getName().toLowerCase(Locale.ROOT));
      }
    }
  }
}
