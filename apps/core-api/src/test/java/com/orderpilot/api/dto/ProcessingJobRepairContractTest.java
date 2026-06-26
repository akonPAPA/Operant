package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.SupportInternalDtos.ProcessingJobRepairExecuteRequest;
import com.orderpilot.api.dto.SupportInternalDtos.ProcessingJobRepairResponse;
import java.lang.reflect.RecordComponent;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-54 — data-boundary contract proof for the bounded processing-job status-repair surface.
 *
 * <p>The execute request carries BUSINESS/SAFE INTENT ONLY. It must never declare request-lifecycle
 * authority (tenant/actor/approver id, approvalStatus/executionStatus), nor any executable payload (raw
 * SQL/script, tableName/fieldName, arbitrary JSON patch, connector credential/secret). It deliberately
 * MAY carry the bounded operational intent fields {@code expectedCurrentStatus}/{@code desiredStatus} —
 * these are matched against the existing {@code ProcessingJobStatus} allowlist by the backend validator and
 * grant the caller no authority over the request itself.
 *
 * <p>The response exposes only operator-safe fields — no secret, raw payload, internal stack trace, or
 * cross-tenant identifier.
 */
class ProcessingJobRepairContractTest {
  // Authority/executable fields the request must never accept. "status" is deliberately NOT here, because
  // expectedCurrentStatus/desiredStatus are bounded business intent — but the lifecycle authority spellings
  // (approvalstatus/executionstatus) and every executable-payload spelling ARE forbidden.
  private static final String[] FORBIDDEN_REQUEST_FIELDS = {
      "tenantid", "actorid", "staffactor", "approver", "approvedby", "rejectedby", "requestedby", "createdby",
      "approvalstatus", "executionstatus", "approval", "sql", "script", "table", "field", "column", "patch",
      "payload", "connector", "secret", "credential", "permission", "role"};

  // The response must never leak a secret or actor authority field.
  private static final String[] FORBIDDEN_RESPONSE_FIELDS = {
      "actorid", "approvedby", "executedby", "secret", "credential", "payload", "sql", "script", "stacktrace"};

  @Test
  void executeRequestCarriesNoAuthorityOrExecutablePayloadField() {
    for (RecordComponent component : ProcessingJobRepairExecuteRequest.class.getRecordComponents()) {
      String name = component.getName().toLowerCase(Locale.ROOT);
      for (String banned : FORBIDDEN_REQUEST_FIELDS) {
        assertThat(name)
            .as("ProcessingJobRepairExecuteRequest.%s must not accept client-owned authority/payload (%s)",
                component.getName(), banned)
            .doesNotContain(banned);
      }
    }
  }

  @Test
  void executeRequestExposesOnlyTheBoundedBusinessIntentFields() {
    Set<String> names = Set.of(
        java.util.Arrays.stream(ProcessingJobRepairExecuteRequest.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toArray(String[]::new));
    assertThat(names).containsExactlyInAnyOrder(
        "processingJobId", "expectedCurrentStatus", "desiredStatus", "reason");
  }

  @Test
  void responseExposesNoSecretOrActorField() {
    for (RecordComponent component : ProcessingJobRepairResponse.class.getRecordComponents()) {
      String name = component.getName().toLowerCase(Locale.ROOT);
      for (String banned : FORBIDDEN_RESPONSE_FIELDS) {
        assertThat(name)
            .as("ProcessingJobRepairResponse.%s must not expose %s", component.getName(), banned)
            .doesNotContain(banned);
      }
    }
  }
}
