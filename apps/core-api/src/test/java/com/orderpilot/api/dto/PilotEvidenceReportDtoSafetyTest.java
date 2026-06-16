package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.Stage10BDtos.PilotDemoScenarioCapabilityResponse;
import com.orderpilot.api.dto.Stage10BDtos.PilotDemoScenarioEvidenceResponse;
import com.orderpilot.api.dto.Stage10BDtos.PilotDemoScenarioPackResponse;
import com.orderpilot.api.dto.Stage10BDtos.PilotDemoScenarioResponse;
import com.orderpilot.api.dto.Stage10BDtos.PilotDemoScenarioSafetyBoundaryResponse;
import com.orderpilot.api.dto.Stage10BDtos.PilotEvidenceReport;
import com.orderpilot.api.dto.Stage10BDtos.PilotReadinessSignal;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * OP-CAP-11G: the pilot evidence report contract must never expose raw prediction/correction
 * payloads, object-storage internals, secrets, or tokens. Pure reflection unit test — no Spring.
 */
class PilotEvidenceReportDtoSafetyTest {
  private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
      "payload", "rawtext", "beforejson", "afterjson", "objectkey", "objectstorage",
      "storagekey", "secret", "token", "credential", "prompt");

  @Test
  void evidenceReportExposesNoRawPayloadOrSecretFields() {
    assertThat(PilotEvidenceReport.class.isRecord()).isTrue();
    assertNoForbiddenComponents(PilotEvidenceReport.class);
    assertNoForbiddenComponents(PilotReadinessSignal.class);
    assertNoForbiddenComponents(Stage10BDtos.ExceptionCategoryResponse.class);
  }

  private static void assertNoForbiddenComponents(Class<?> recordType) {
    for (RecordComponent component : recordType.getRecordComponents()) {
      String name = component.getName().toLowerCase(Locale.ROOT);
      assertThat(FORBIDDEN_FRAGMENTS.stream().anyMatch(name::contains))
          .as("DTO %s exposes forbidden field '%s'", recordType.getSimpleName(), component.getName())
          .isFalse();
    }
  }

  @Test
  void demoScenarioPackExposesNoRawPayloadOrSecretFields() {
    assertThat(PilotDemoScenarioPackResponse.class.isRecord()).isTrue();
    assertNoForbiddenComponents(PilotDemoScenarioPackResponse.class);
    assertNoForbiddenComponents(PilotDemoScenarioResponse.class);
    assertNoForbiddenComponents(PilotDemoScenarioCapabilityResponse.class);
    assertNoForbiddenComponents(PilotDemoScenarioEvidenceResponse.class);
    assertNoForbiddenComponents(PilotDemoScenarioSafetyBoundaryResponse.class);
  }

  @Test
  void evidenceReportCarriesStructuredEvidenceFields() {
    List<String> componentNames = Arrays.stream(PilotEvidenceReport.class.getRecordComponents())
        .map(RecordComponent::getName)
        .toList();
    // Sanity: the report still carries the structured evidence the report pack is built from.
    assertThat(componentNames)
        .contains("estimatedMinutesSaved", "estimatedCostSaved", "exceptionBreakdown",
            "readinessSignals", "limitations", "safetyStatement");
  }
}
