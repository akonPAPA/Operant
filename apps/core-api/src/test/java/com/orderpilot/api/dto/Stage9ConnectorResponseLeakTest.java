package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.Stage9Dtos.Stage9ChangeRequestCreateRequest;
import com.orderpilot.api.dto.Stage9Dtos.Stage9ChangeRequestResponse;
import com.orderpilot.api.dto.Stage9Dtos.Stage9ConnectorAuditEventResponse;
import com.orderpilot.api.dto.Stage9Dtos.Stage9ConnectorPolicyResponse;
import com.orderpilot.api.dto.Stage9Dtos.Stage9ConnectorSyncRunResponse;
import com.orderpilot.api.dto.Stage9Dtos.Stage9ExecutionSafetyResponse;
import com.orderpilot.api.dto.Stage9Dtos.Stage9IntegrationConnectionResponse;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class Stage9ConnectorResponseLeakTest {

  private static List<String> componentsOf(Class<?> recordType) {
    return Arrays.stream(recordType.getRecordComponents()).map(RecordComponent::getName).toList();
  }

  @Test
  void stage9ChangeRequestResponseDoesNotExposeAuthorityOrConnectorInternals() {
    assertThat(componentsOf(Stage9ChangeRequestResponse.class)).doesNotContain(
        "sourceId",
        "createdByUserId",
        "approvedByUserId",
        "connectorIdempotencyKeyHash",
        "connectorAttemptCount",
        "connectorMaxAttempts",
        "connectorLastAttemptAt",
        "connectorNextRetryAt",
        "failureReason",
        // Wave 01H Category D: internal connector/execution machinery is not exposed.
        "executionStatus",
        "connectorFailureType",
        "connectorRetryable");
    // The business-facing rollup status and approval/validation status remain.
    assertThat(componentsOf(Stage9ChangeRequestResponse.class)).contains("status", "approvalStatus", "validationStatus");
  }

  @Test
  void stage9ChangeRequestCreateRequestDoesNotAcceptPayloadOrAuthority() {
    // Wave 01H Category C: the external-write payload is backend-owned and the actor is server-resolved;
    // the public create request carries business intent only.
    assertThat(componentsOf(Stage9ChangeRequestCreateRequest.class)).doesNotContain(
        "requestPayloadJson",
        "payloadJson",
        "idempotencyKey",
        "actorId",
        "actorUserId",
        "createdByUserId",
        "tenantId");
    assertThat(componentsOf(Stage9ChangeRequestCreateRequest.class)).contains("sourceType", "sourceId", "requestedAction");
  }

  @Test
  void stage9ConnectorPolicyAndSafetyResponsesDoNotExposeCredentialsCapabilitiesOrHashes() {
    assertThat(componentsOf(Stage9ConnectorPolicyResponse.class)).doesNotContain(
        "capabilities",
        "credentialStatus",
        "maskedCredentialRef",
        "connectorSecret");
    assertThat(componentsOf(Stage9ExecutionSafetyResponse.class)).doesNotContain(
        "changeRequestId",
        "capabilities",
        "connectorIdempotencyKeyHash",
        "failureMessage",
        "credentialStatus",
        "maskedCredentialRef",
        "connectorSecret");
  }

  @Test
  void stage9IntegrationSyncAndAuditResponsesDoNotExposeInternalConnectorHandles() {
    assertThat(componentsOf(Stage9IntegrationConnectionResponse.class)).doesNotContain("endpointRef", "secretRef", "credentialRef");
    assertThat(componentsOf(Stage9ConnectorSyncRunResponse.class)).doesNotContain("integrationConnectionId", "errorMessage", "rawPayload");
    assertThat(componentsOf(Stage9ConnectorAuditEventResponse.class)).doesNotContain("id", "entityId", "metadata", "rawPayload");
  }
}
