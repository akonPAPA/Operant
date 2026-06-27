package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.SupportTenantLocatorDtos.SupportTenantContextResponse;
import com.orderpilot.api.dto.SupportTenantLocatorDtos.SupportTenantLocatorResult;
import com.orderpilot.api.dto.SupportTenantLocatorDtos.SupportTenantSearchResponse;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-57 — response-contract proof for the internal tenant locator + support context surface. These DTOs
 * expose only bounded operator-safe display/handle/scope/timestamp fields; they carry no secret, credential,
 * token, api key, password, connector internal, raw payload, raw customer data, actor id, grant id, audit
 * row id, or storage/source id.
 */
class SupportTenantLocatorContractTest {
  private static final String[] FORBIDDEN_RESPONSE_FIELDS = {
      "actor", "requestedby", "approvedby", "rejectedby", "executedby", "createdby", "revokedby",
      "audit", "payload", "raw", "secret", "credential", "token", "stack", "sql", "script",
      "connector", "storage", "sourceid", "idempotency", "apikey", "password", "grantid"
  };

  @Test
  void responseDtosExposeNoInternalLeakFieldNames() {
    assertNoLeakFields(SupportTenantLocatorResult.class);
    assertNoLeakFields(SupportTenantSearchResponse.class);
    assertNoLeakFields(SupportTenantContextResponse.class);
  }

  @Test
  void serializedResponsesContainNoSecretLikeFields() throws Exception {
    UUID tenantId = UUID.randomUUID();
    SupportTenantSearchResponse search = new SupportTenantSearchResponse(
        "acme",
        0,
        20,
        1,
        false,
        List.of(new SupportTenantLocatorResult(
            tenantId,
            "Acme Distribution",
            "acme",
            "ACTIVE",
            List.of("DIAGNOSTICS"),
            Instant.parse("2026-06-26T18:00:00Z"),
            true,
            "DISABLED")),
        Instant.parse("2026-06-26T12:00:01Z"));
    SupportTenantContextResponse context = new SupportTenantContextResponse(
        tenantId,
        "Acme Distribution",
        "acme",
        "ACTIVE",
        List.of("DIAGNOSTICS"),
        Instant.parse("2026-06-26T18:00:00Z"),
        true,
        true,
        "DISABLED",
        Instant.parse("2026-06-26T12:00:01Z"));

    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    String searchJson = mapper.writeValueAsString(search).toLowerCase(Locale.ROOT);
    String contextJson = mapper.writeValueAsString(context).toLowerCase(Locale.ROOT);

    for (String json : List.of(searchJson, contextJson)) {
      assertThat(json).contains("disabled");
      assertThat(json).doesNotContain(
          "secret", "credential", "token", "payload", "stacktrace", "sql", "script",
          "apikey", "password", "connectorsecret");
      assertThat(json).doesNotContain("requestedby", "approvedby", "executedby", "audit", "grantid", "actorid");
    }
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
