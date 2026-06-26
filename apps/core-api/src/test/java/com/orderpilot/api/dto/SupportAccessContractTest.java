package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.SupportInternalDtos.CreateSupportAccessGrantRequest;
import com.orderpilot.api.dto.SupportInternalDtos.DataRepairApprovalDecisionRequest;
import com.orderpilot.api.dto.SupportInternalDtos.DataRepairApprovalRequest;
import com.orderpilot.api.dto.SupportInternalDtos.DataRepairDryRunRequest;
import com.orderpilot.api.dto.SupportInternalDtos.MaintenanceActionRecordRequest;
import com.orderpilot.api.dto.SupportInternalDtos.SupportGrantApprovalDecisionRequest;
import java.lang.reflect.RecordComponent;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-51 — request-contract proof. The support request DTOs carry business intent only: they never
 * declare a tenant id, acting/staff actor id, status, expiry, approval state, execution authority, or audit
 * metadata field. The backend owns all of those, resolving the target tenant from the trusted
 * {@code X-Tenant-Id} context and the acting staff actor from the trusted actor header — so a forged body
 * field is simply an unknown property the backend ignores and can never use to grant or widen access.
 */
class SupportAccessContractTest {
  private static final String[] FORBIDDEN_AUTHORITY_FIELDS = {
      "tenantid", "actorid", "staffactor", "createdby", "requestedby", "approvedby", "rejectedby",
      "status", "expiresat", "executionstatus", "approval", "grantid", "auditmetadata", "permission",
      "role", "sql", "script", "tablename", "connector", "secret", "credential"};

  @Test
  void createGrantRequestCarriesNoAuthorityField() {
    assertNoAuthorityFields(CreateSupportAccessGrantRequest.class);
  }

  @Test
  void maintenanceRequestCarriesNoAuthorityField() {
    assertNoAuthorityFields(MaintenanceActionRecordRequest.class);
  }

  @Test
  void dataRepairRequestCarriesNoAuthorityField() {
    assertNoAuthorityFields(DataRepairDryRunRequest.class);
  }

  // --- OP-CAP-52 approval request DTOs carry business intent only (no forged authority/decision state) ---

  @Test
  void supportGrantApprovalDecisionRequestCarriesNoAuthorityField() {
    assertNoAuthorityFields(SupportGrantApprovalDecisionRequest.class);
  }

  @Test
  void dataRepairApprovalRequestCarriesNoAuthorityOrRawTargetField() {
    assertNoAuthorityFields(DataRepairApprovalRequest.class);
  }

  @Test
  void dataRepairApprovalDecisionRequestCarriesNoAuthorityField() {
    assertNoAuthorityFields(DataRepairApprovalDecisionRequest.class);
  }

  private static void assertNoAuthorityFields(Class<?> recordType) {
    for (RecordComponent component : recordType.getRecordComponents()) {
      String name = component.getName().toLowerCase(Locale.ROOT);
      for (String banned : FORBIDDEN_AUTHORITY_FIELDS) {
        assertThat(name)
            .as("%s.%s must not accept client-owned authority (%s)", recordType.getSimpleName(),
                component.getName(), banned)
            .doesNotContain(banned);
      }
    }
  }
}
