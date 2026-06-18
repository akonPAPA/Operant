package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.Stage10CDtos.ChangeRequestCreateRequest;
import com.orderpilot.api.dto.Stage10CDtos.ChangeRequestResponse;
import com.orderpilot.api.dto.Stage10CDtos.OutboxEventResponse;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

// OP-CAP-31: the default operator change-request / outbox responses must not leak the raw
// external-write payload, dedupe/integrity internals, or internal actor ids. The create request
// must not accept the creator authority field. Contract-shape proof via record components.
class ChangeRequestSafeResponseDtoTest {

  private static List<String> componentsOf(Class<?> recordType) {
    return Arrays.stream(recordType.getRecordComponents()).map(RecordComponent::getName).toList();
  }

  @Test
  void changeRequestDefaultResponseDoesNotLeakPayloadOrInternalActorIds() {
    List<String> components = componentsOf(ChangeRequestResponse.class);
    assertThat(components).doesNotContain(
        "requestPayloadJson",
        "idempotencyKey",
        "payloadHash",
        "createdByUserId",
        "approvedByUserId");
  }

  @Test
  void changeRequestDefaultResponseStillExposesOperatorSafeFields() {
    List<String> components = componentsOf(ChangeRequestResponse.class);
    assertThat(components).contains(
        "id",
        "targetSystem",
        "requestedAction",
        "validationStatus",
        "approvalStatus",
        "executionStatus",
        "externalReference");
  }

  @Test
  void outboxDefaultResponseDoesNotLeakRawPayload() {
    List<String> components = componentsOf(OutboxEventResponse.class);
    assertThat(components).doesNotContain("payloadJson");
    assertThat(components).contains("id", "aggregateType", "eventType", "status");
  }

  @Test
  void changeRequestCreateRequestDoesNotAcceptCreatorAuthority() {
    List<String> components = componentsOf(ChangeRequestCreateRequest.class);
    assertThat(components).doesNotContain("createdByUserId", "approvedByUserId", "tenantId", "actorId");
  }
}
