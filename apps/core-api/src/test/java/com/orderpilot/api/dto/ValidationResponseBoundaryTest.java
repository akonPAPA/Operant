package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.Stage2Dtos.ValidationReportResponse;
import com.orderpilot.api.dto.Stage5Dtos.ApprovalRequirementResponse;
import com.orderpilot.api.dto.Stage5Dtos.CustomerMatchResponse;
import com.orderpilot.api.dto.Stage5Dtos.DiscountCheckResponse;
import com.orderpilot.api.dto.Stage5Dtos.InventoryCheckResponse;
import com.orderpilot.api.dto.Stage5Dtos.MarginCheckResponse;
import com.orderpilot.api.dto.Stage5Dtos.PriceCheckResponse;
import com.orderpilot.api.dto.Stage5Dtos.ProductMatchValidationResponse;
import com.orderpilot.api.dto.Stage5Dtos.SubstituteCandidateResponse;
import com.orderpilot.api.dto.Stage5Dtos.UomNormalizationResponse;
import com.orderpilot.api.dto.Stage5Dtos.ValidationIssueResponse;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ValidationResponseBoundaryTest {
  private static final Set<String> FORBIDDEN = Set.of(
      "tenantId", "detailsJson", "candidatesJson", "snapshotId", "unitCost",
      "payloadJson", "requestPayloadJson", "idempotencyKey", "auditCorrelationId",
      "createdBy", "approvedBy", "reviewedBy", "decidedBy", "secretRef",
      "secretReferenceId");

  @Test
  void validationAndImportResponsesExcludeInternalFields() {
    for (Class<?> response : Set.of(
        ValidationReportResponse.class,
        ValidationIssueResponse.class,
        ApprovalRequirementResponse.class,
        SubstituteCandidateResponse.class,
        CustomerMatchResponse.class,
        ProductMatchValidationResponse.class,
        UomNormalizationResponse.class,
        InventoryCheckResponse.class,
        PriceCheckResponse.class,
        DiscountCheckResponse.class,
        MarginCheckResponse.class)) {
      assertThat(componentNames(response))
          .as(response.getSimpleName())
          .doesNotContainAnyElementsOf(FORBIDDEN);
    }
  }

  private static Set<String> componentNames(Class<?> recordType) {
    return Arrays.stream(recordType.getRecordComponents())
        .map(component -> component.getName())
        .collect(Collectors.toSet());
  }
}
