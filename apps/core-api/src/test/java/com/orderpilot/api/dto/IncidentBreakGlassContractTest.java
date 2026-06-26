package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.IncidentInternalDtos.BreakGlassDecisionRequest;
import com.orderpilot.api.dto.IncidentInternalDtos.BreakGlassResponse;
import com.orderpilot.api.dto.IncidentInternalDtos.CloseIncidentRequest;
import com.orderpilot.api.dto.IncidentInternalDtos.CreateBreakGlassRequest;
import com.orderpilot.api.dto.IncidentInternalDtos.CreateIncidentRequest;
import com.orderpilot.api.dto.IncidentInternalDtos.IncidentResponse;
import java.lang.reflect.RecordComponent;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-53 — request/response contract proof for the incident-response / break-glass surface. The request
 * DTOs carry business intent only: they never declare a tenant id, an acting/approving staff actor, a status,
 * an expiry, an approval/decision state, audit metadata, or any SQL/script/command/connector/secret authority
 * field — the backend owns all of those. The response DTOs expose no secret-like field (secret/credential/
 * token/raw SQL/payload) and no actor identity, so an operator-safe view can never leak internal authority.
 */
class IncidentBreakGlassContractTest {
  private static final String[] FORBIDDEN_REQUEST_FIELDS = {
      "tenantid", "actorid", "staffactor", "createdby", "requestedby", "approvedby", "rejectedby",
      "status", "expiresat", "executionstatus", "approval", "incidentid", "requestid", "auditmetadata",
      "permission", "role", "sql", "script", "command", "tablename", "connector", "secret", "credential",
      "token", "payload"};

  private static final String[] FORBIDDEN_RESPONSE_FIELDS = {
      "secret", "credential", "token", "sql", "script", "command", "payload", "password",
      "approvedby", "rejectedby", "requestedby", "createdby", "staffactor", "auditmetadata"};

  @Test
  void createIncidentRequestCarriesNoAuthorityField() {
    assertNoFields(CreateIncidentRequest.class, FORBIDDEN_REQUEST_FIELDS);
  }

  @Test
  void closeIncidentRequestCarriesNoAuthorityField() {
    assertNoFields(CloseIncidentRequest.class, FORBIDDEN_REQUEST_FIELDS);
  }

  @Test
  void createBreakGlassRequestCarriesNoAuthorityOrRawTargetField() {
    assertNoFields(CreateBreakGlassRequest.class, FORBIDDEN_REQUEST_FIELDS);
  }

  @Test
  void breakGlassDecisionRequestCarriesNoAuthorityField() {
    assertNoFields(BreakGlassDecisionRequest.class, FORBIDDEN_REQUEST_FIELDS);
  }

  @Test
  void incidentResponseExposesNoSecretOrActorField() {
    assertNoFields(IncidentResponse.class, FORBIDDEN_RESPONSE_FIELDS);
  }

  @Test
  void breakGlassResponseExposesNoSecretOrActorField() {
    assertNoFields(BreakGlassResponse.class, FORBIDDEN_RESPONSE_FIELDS);
  }

  private static void assertNoFields(Class<?> recordType, String[] forbidden) {
    for (RecordComponent component : recordType.getRecordComponents()) {
      String name = component.getName().toLowerCase(Locale.ROOT);
      for (String banned : forbidden) {
        assertThat(name)
            .as("%s.%s must not contain forbidden token (%s)", recordType.getSimpleName(),
                component.getName(), banned)
            .doesNotContain(banned);
      }
    }
  }
}
