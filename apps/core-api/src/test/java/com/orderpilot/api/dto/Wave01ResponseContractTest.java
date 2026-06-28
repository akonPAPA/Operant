package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.AiMemoryDtos.AiMemoryInvalidationEventDto;
import com.orderpilot.api.dto.Stage6Dtos.WorkspaceDraftOrderDto;
import com.orderpilot.api.dto.Stage6Dtos.WorkspaceDraftOrderLineDto;
import com.orderpilot.api.dto.Stage6Dtos.WorkspaceDraftQuoteDto;
import com.orderpilot.api.dto.Stage6Dtos.WorkspaceDraftQuoteLineDto;
import com.orderpilot.api.dto.Stage6Dtos.WorkspaceNoteDto;
import java.lang.reflect.RecordComponent;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Wave 01A — response contract proof.
 * Public/default response DTOs must not expose internal actor identity, tenant,
 * audit, idempotency, source internal, or raw payload fields.
 */
class Wave01ResponseContractTest {
  private static final Set<String> FORBIDDEN = Set.of(
      "actorid", "actortype", "staffuserid", "staffactorid",
      "tenantid", "auditcorrelationid", "idempotencykey",
      "payloadjson", "payloadhash", "createdby", "approvedby",
      "sourcevalidationrunid", "sourceexceptioncaseid",
      "sourceextractionresultid", "sourcemessageid",
      "sourcedocumentid", "sourcetype", "notes");

  @Test
  void aiMemoryInvalidationEventDtoHasNoActorOrInternalFields() {
    assertNoForbiddenFields(AiMemoryInvalidationEventDto.class);
  }

  @Test
  void aiMemoryInvalidationEventDtoHasNoActorId() {
    Set<String> names = componentNames(AiMemoryInvalidationEventDto.class);
    assertThat(names).doesNotContain("actorid");
    assertThat(names).doesNotContain("actortype");
  }

  @Test
  void workspaceNoteDtoHasNoTenantOrActorFields() {
    assertNoForbiddenFields(WorkspaceNoteDto.class);
    Set<String> names = componentNames(WorkspaceNoteDto.class);
    assertThat(names).doesNotContain("tenantid");
    assertThat(names).doesNotContain("createdby");
  }

  @Test
  void workspaceDraftQuoteDtoHasNoInternalLeaks() {
    assertNoForbiddenFields(WorkspaceDraftQuoteDto.class);
    Set<String> names = componentNames(WorkspaceDraftQuoteDto.class);
    assertThat(names).doesNotContain("tenantid");
    assertThat(names).doesNotContain("idempotencykey");
    assertThat(names).doesNotContain("auditcorrelationid");
    assertThat(names).doesNotContain("approvedby");
    assertThat(names).doesNotContain("sourcevalidationrunid");
    assertThat(names).doesNotContain("sourceexceptioncaseid");
  }

  @Test
  void workspaceDraftQuoteLineDtoHasNoInternalLeaks() {
    assertNoForbiddenFields(WorkspaceDraftQuoteLineDto.class);
  }

  @Test
  void workspaceDraftOrderDtoHasNoInternalLeaks() {
    assertNoForbiddenFields(WorkspaceDraftOrderDto.class);
    Set<String> names = componentNames(WorkspaceDraftOrderDto.class);
    assertThat(names).doesNotContain("tenantid");
    assertThat(names).doesNotContain("approvedby");
    assertThat(names).doesNotContain("sourcevalidationrunid");
    assertThat(names).doesNotContain("sourceexceptioncaseid");
  }

  @Test
  void workspaceDraftOrderLineDtoHasNoInternalLeaks() {
    assertNoForbiddenFields(WorkspaceDraftOrderLineDto.class);
  }

  private static void assertNoForbiddenFields(Class<?> recordType) {
    for (RecordComponent c : recordType.getRecordComponents()) {
      String name = c.getName().toLowerCase(Locale.ROOT);
      assertThat(FORBIDDEN)
          .as("%s.%s must not leak internal/actor/tenant/audit/source fields", recordType.getSimpleName(), c.getName())
          .doesNotContain(name);
    }
  }

  private static Set<String> componentNames(Class<?> recordType) {
    return java.util.Arrays.stream(recordType.getRecordComponents())
        .map(c -> c.getName().toLowerCase(Locale.ROOT))
        .collect(java.util.stream.Collectors.toSet());
  }
}
