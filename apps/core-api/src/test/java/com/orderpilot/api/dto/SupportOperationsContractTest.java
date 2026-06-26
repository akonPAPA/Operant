package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.SupportOperationsDtos.DataRepairOperationsViewResponse;
import com.orderpilot.api.dto.SupportOperationsDtos.SupportOperationsSummaryResponse;
import com.orderpilot.api.dto.SupportOperationsDtos.SupportOperationsTimelineEntry;
import com.orderpilot.api.dto.SupportOperationsDtos.SupportOperationsTimelineResponse;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-55 — response-contract proof for the internal support operations visibility surface. These DTOs
 * expose only bounded operator-safe lifecycle/count fields; they do not carry raw payloads, actor ids,
 * audit row ids, connector credentials, SQL/script bodies, or stack traces.
 */
class SupportOperationsContractTest {
  private static final String[] FORBIDDEN_RESPONSE_FIELDS = {
      "actor", "requestedby", "approvedby", "rejectedby", "executedby", "createdby", "revokedby",
      "audit", "payload", "raw", "secret", "credential", "token", "stack", "sql", "script",
      "connector", "storage", "idempotency", "attempt", "error"
  };

  @Test
  void responseDtosExposeNoInternalLeakFieldNames() {
    assertNoLeakFields(SupportOperationsSummaryResponse.class);
    assertNoLeakFields(SupportOperationsTimelineEntry.class);
    assertNoLeakFields(SupportOperationsTimelineResponse.class);
    assertNoLeakFields(DataRepairOperationsViewResponse.class);
  }

  @Test
  void serializedDetailContainsNoSecretLikeFields() throws Exception {
    UUID tenantId = UUID.randomUUID();
    DataRepairOperationsViewResponse response = new DataRepairOperationsViewResponse(
        UUID.randomUUID(),
        tenantId,
        "PROCESSING_JOB_STATUS_REPAIR",
        "APPROVED",
        "EXECUTED",
        "DRY_RUN_COMPLETED",
        "1 stuck processing job",
        UUID.randomUUID(),
        "PROCESSING",
        "FAILED",
        Instant.parse("2026-06-26T12:00:00Z"),
        true,
        List.of(new SupportOperationsTimelineEntry(
            "PROCESSING_JOB_REPAIR",
            "PROCESSING_JOB_REPAIR_EXECUTED",
            UUID.randomUUID(),
            "EXECUTED",
            Instant.parse("2026-06-26T12:00:00Z"))),
        Instant.parse("2026-06-26T12:00:01Z"),
        "DISABLED");

    String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response).toLowerCase(Locale.ROOT);

    assertThat(json).contains("processing_job_status_repair", "disabled");
    assertThat(json).doesNotContain("secret", "credential", "token", "payload", "stacktrace", "sql", "script");
    assertThat(json).doesNotContain("requestedby", "approvedby", "executedby", "audit");
  }

  private static void assertNoLeakFields(Class<?> recordType) {
    for (RecordComponent component : recordType.getRecordComponents()) {
      String name = component.getName().toLowerCase(Locale.ROOT);
      for (String banned : FORBIDDEN_RESPONSE_FIELDS) {
        assertThat(name)
            .as("%s.%s must not expose internal/leaky field name (%s)",
                recordType.getSimpleName(), component.getName(), banned)
            .doesNotContain(banned);
      }
    }
  }
}
