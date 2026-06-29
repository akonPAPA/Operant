package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.Stage11ADtos.DraftQuoteLineResponse;
import com.orderpilot.api.dto.Stage11ADtos.DraftQuoteResponse;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Wave 01H Category D guard. The draft quote screen contract is operator-safe: the response must not
 * expose the tenant, the raw internal source/storage ids, the internal customer account id, or the
 * raw internal actor that decided a substitute. Fast reflection check (no Spring context).
 */
class Stage11ADraftQuoteResponseLeakTest {

  private static List<String> componentsOf(Class<?> recordType) {
    return Arrays.stream(recordType.getRecordComponents()).map(RecordComponent::getName).toList();
  }

  @Test
  void draftQuoteResponseDoesNotLeakTenantSourceOrInternalIds() {
    List<String> components = componentsOf(DraftQuoteResponse.class);
    assertThat(components).doesNotContain(
        "tenantId",
        "sourceMessageId",
        "sourceDocumentId",
        "customerAccountId",
        "createdByUserId",
        "actorId",
        "auditCorrelationId",
        "idempotencyKey");
    // Safe-to-display business fields are retained.
    assertThat(components).contains(
        "id", "quoteNumber", "sourceType", "customerDisplayName", "status", "validationStatus", "lines", "issues");
  }

  @Test
  void draftQuoteLineResponseDoesNotLeakRawSubstituteActor() {
    List<String> components = componentsOf(DraftQuoteLineResponse.class);
    assertThat(components).doesNotContain("substituteDecidedBy", "actorId", "actorUserId");
    // The business-facing substitute decision summary is retained.
    assertThat(components).contains(
        "substituteDecisionStatus", "substituteDecisionReasonCode", "substituteDecidedAt", "substituteDecisionNote");
  }
}
