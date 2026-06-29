package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.orderpilot.api.dto.Stage9Dtos.Stage9ConnectorAuditEventResponse;
import com.orderpilot.api.dto.Stage9Dtos.Stage9ConnectorAuditResponse;
import com.orderpilot.api.dto.Stage9Dtos.Stage9ConnectorPolicyResponse;
import com.orderpilot.api.dto.Stage9Dtos.Stage9ExecutionSafetyResponse;
import com.orderpilot.application.services.integration.ChangeRequestService;
import com.orderpilot.application.services.integration.ConnectorExecutionSafetyService;
import com.orderpilot.application.services.integration.ConnectorSyncEventService;
import com.orderpilot.application.services.integration.IntegrationConnectionService;
import com.orderpilot.domain.integration.*;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
import com.orderpilot.security.RequestActorResolver;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(Stage9IntegrationController.class)
@Import({CoreConfiguration.class, ApiSecurityWebConfig.class, ApiPermissionInterceptor.class, ApiPermissionGuard.class, RequestActorResolver.class})
class Stage9IntegrationControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private IntegrationConnectionService integrationConnectionService;
  @MockBean private ChangeRequestService changeRequestService;
  @MockBean private ConnectorSyncEventService connectorSyncEventService;
  @MockBean private ConnectorExecutionSafetyService safetyService;

  @Test
  void stage9EndpointsReturnIntegrationControlContracts() throws Exception {
    UUID sourceId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID connectionId = UUID.randomUUID();
    Instant now = Instant.parse("2026-05-27T00:00:00Z");
    IntegrationConnection connection = new IntegrationConnection(UUID.randomUUID(), IntegrationProviderType.OTHER_ERP, "Demo ERP Adapter", "DEMO_ERP_LOCAL", null, "demo://local", now);
    connection.activate(now);
    ChangeRequest changeRequest = new ChangeRequest(UUID.randomUUID(), "DEMO_ERP", "DRAFT_QUOTE", "CREATE_DRAFT_QUOTE", "DRAFT_QUOTE", sourceId, "{}", "key", null, now);
    changeRequest.approve(UUID.randomUUID(), now);
    changeRequest.markExecuted("DEMO-QUOTE-123", "sha256:abc123", now);
    ConnectorSyncEvent sync = new ConnectorSyncEvent(UUID.randomUUID(), connectionId, IntegrationProviderType.OTHER_ERP, "CREATE_DRAFT_QUOTE", "OUTBOUND_DEMO", now);
    sync.complete(0, 0, 0, now);
    when(integrationConnectionService.list()).thenReturn(List.of(connection));
    when(integrationConnectionService.get(any())).thenReturn(connection);
    when(integrationConnectionService.createDraft(eq(IntegrationProviderType.OTHER_ERP), anyString(), eq("DEMO_ERP_LOCAL"), isNull(), eq("demo://local"))).thenReturn(connection);
    when(integrationConnectionService.activate(any())).thenReturn(connection);
    when(changeRequestService.listChangeRequests()).thenReturn(List.of(changeRequest));
    when(changeRequestService.getChangeRequest(any())).thenReturn(changeRequest);
    when(changeRequestService.createStage9DemoChangeRequest(anyString(), any(), anyString(), nullable(String.class), any())).thenReturn(changeRequest);
    when(changeRequestService.approveChangeRequest(any(), any())).thenReturn(changeRequest);
    when(changeRequestService.rejectChangeRequest(any(), any())).thenReturn(changeRequest);
    when(changeRequestService.executeStage9DemoChangeRequest(any())).thenReturn(changeRequest);
    when(connectorSyncEventService.list()).thenReturn(List.of(sync));

    mockMvc.perform(get("/api/stage9/integrations").header(ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.integrations[0].displayName").value("Demo ERP Adapter"))
        .andExpect(jsonPath("$.integrations[0].mode").value("READ_ONLY"))
        .andExpect(jsonPath("$.integrations[0].endpointRef").doesNotExist());
    mockMvc.perform(post("/api/stage9/integrations/demo-erp").header(ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_MANAGE").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connectionKind").value("DEMO_ERP_LOCAL"));
    mockMvc.perform(get("/api/stage9/change-requests").header(ApiPermissionGuard.PERMISSIONS_HEADER, "CHANGE_REQUEST_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changeRequests[0].status").value("EXECUTED"))
        .andExpect(jsonPath("$.changeRequests[0].externalReference").value("DEMO-QUOTE-123"))
        .andExpect(jsonPath("$.changeRequests[0].sourceId").doesNotExist())
        .andExpect(jsonPath("$.changeRequests[0].createdByUserId").doesNotExist())
        .andExpect(jsonPath("$.changeRequests[0].approvedByUserId").doesNotExist())
        .andExpect(jsonPath("$.changeRequests[0].connectorIdempotencyKeyHash").doesNotExist())
        // Wave 01H Category D: internal execution machinery is not exposed; business `status` rolls it up.
        .andExpect(jsonPath("$.changeRequests[0].executionStatus").doesNotExist())
        .andExpect(jsonPath("$.changeRequests[0].connectorFailureType").doesNotExist())
        .andExpect(jsonPath("$.changeRequests[0].connectorRetryable").doesNotExist());
    mockMvc.perform(post("/api/stage9/change-requests").header(ApiPermissionGuard.PERMISSIONS_HEADER, "CHANGE_REQUEST_CREATE").contentType(MediaType.APPLICATION_JSON).content("{\"sourceType\":\"DRAFT_QUOTE\",\"sourceId\":\"" + sourceId + "\",\"requestedAction\":\"CREATE_DRAFT_QUOTE\",\"requestPayloadJson\":\"{}\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetSystem").value("DEMO_ERP"));
    mockMvc.perform(post("/api/stage9/change-requests/" + requestId + "/approve").header(ApiPermissionGuard.PERMISSIONS_HEADER, "CHANGE_REQUEST_APPROVE").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalStatus").value("APPROVED"));
    mockMvc.perform(post("/api/stage9/change-requests/" + requestId + "/execute").header(ApiPermissionGuard.PERMISSIONS_HEADER, "CHANGE_REQUEST_EXECUTE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("EXECUTED"))
        .andExpect(jsonPath("$.executionStatus").doesNotExist());
    mockMvc.perform(get("/api/stage9/connector-sync-runs").header(ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.syncRuns[0].direction").value("OUTBOUND_DEMO"))
        .andExpect(jsonPath("$.syncRuns[0].integrationConnectionId").doesNotExist())
        .andExpect(jsonPath("$.syncRuns[0].errorMessage").doesNotExist());
  }

  @Test
  void demoErpIntegrationMutationRejectsReadOnlyPermissionBeforeServiceInvocation() throws Exception {
    mockMvc.perform(post("/api/stage9/integrations/demo-erp")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_READ")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden());

    verify(integrationConnectionService, never()).createDraft(any(), anyString(), anyString(), any(), anyString());
    verify(integrationConnectionService, never()).activate(any());
  }

  @Test
  void stage9ConnectorPolicySafetyAndAuditResponsesDoNotExposeInternals() throws Exception {
    UUID requestId = UUID.randomUUID();
    Instant now = Instant.parse("2026-05-27T00:00:00Z");
    when(safetyService.policies()).thenReturn(new Stage9ConnectorPolicyResponse("DEMO_ONLY", false, false, "External writes disabled."));
    when(safetyService.safety(requestId)).thenReturn(new Stage9ExecutionSafetyResponse("DEMO_ONLY", 1, 3, now, null, "TRANSIENT_ERROR", true, true, true, false, false));
    when(safetyService.audit()).thenReturn(new Stage9ConnectorAuditResponse(List.of(new Stage9ConnectorAuditEventResponse("CHANGE_REQUEST_CREATED", "CHANGE_REQUEST", now))));

    mockMvc.perform(get("/api/stage9/connectors/policies").header(ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.executionMode").value("DEMO_ONLY"))
        .andExpect(jsonPath("$.capabilities").doesNotExist())
        .andExpect(jsonPath("$.credentialStatus").doesNotExist())
        .andExpect(jsonPath("$.maskedCredentialRef").doesNotExist());

    mockMvc.perform(get("/api/stage9/change-requests/" + requestId + "/execution-safety")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "CHANGE_REQUEST_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.executionMode").value("DEMO_ONLY"))
        .andExpect(jsonPath("$.changeRequestId").doesNotExist())
        .andExpect(jsonPath("$.capabilities").doesNotExist())
        .andExpect(jsonPath("$.connectorIdempotencyKeyHash").doesNotExist())
        .andExpect(jsonPath("$.failureMessage").doesNotExist())
        .andExpect(jsonPath("$.credentialStatus").doesNotExist())
        .andExpect(jsonPath("$.maskedCredentialRef").doesNotExist());

    mockMvc.perform(get("/api/stage9/connector-audit").header(ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.events[0].action").value("CHANGE_REQUEST_CREATED"))
        .andExpect(jsonPath("$.events[0].id").doesNotExist())
        .andExpect(jsonPath("$.events[0].entityId").doesNotExist())
        .andExpect(jsonPath("$.events[0].metadata").doesNotExist());
  }

  // OP-CAP-17E: the connector ChangeRequest approver must be taken from the trusted actor header,
  // never from a body-supplied actorId. A request cannot forge who approved a connector write.
  @Test
  void connectorApprovalActorComesFromTrustedHeaderNotRequestBody() throws Exception {
    UUID requestId = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();
    Instant now = Instant.parse("2026-05-27T00:00:00Z");
    ChangeRequest changeRequest = new ChangeRequest(UUID.randomUUID(), "DEMO_ERP", "DRAFT_QUOTE", "CREATE_DRAFT_QUOTE", "DRAFT_QUOTE", UUID.randomUUID(), "{}", "key", null, now);
    changeRequest.approve(trustedActor, now);
    when(changeRequestService.approveChangeRequest(eq(requestId), any())).thenReturn(changeRequest);

    mockMvc.perform(post("/api/stage9/change-requests/" + requestId + "/approve")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "CHANGE_REQUEST_APPROVE")
            .header(RequestActorResolver.ACTOR_HEADER, trustedActor.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"actorId\":\"" + spoofActor + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalStatus").value("APPROVED"));

    ArgumentCaptor<UUID> actorCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(changeRequestService).approveChangeRequest(eq(requestId), actorCaptor.capture());
    assertThat(actorCaptor.getValue()).isEqualTo(trustedActor).isNotEqualTo(spoofActor);
  }

  // OP-CAP-17F: the connector ChangeRequest creator must be taken from the trusted actor header,
  // never from a body-supplied actorId. A request cannot forge who created a connector write request.
  @Test
  void connectorCreateActorComesFromTrustedHeaderNotRequestBody() throws Exception {
    UUID sourceId = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();
    Instant now = Instant.parse("2026-05-27T00:00:00Z");
    ChangeRequest changeRequest = new ChangeRequest(UUID.randomUUID(), "DEMO_ERP", "DRAFT_QUOTE", "CREATE_DRAFT_QUOTE", "DRAFT_QUOTE", sourceId, "{}", "key", trustedActor, now);
    when(changeRequestService.createStage9DemoChangeRequest(anyString(), any(), anyString(), nullable(String.class), any())).thenReturn(changeRequest);

    mockMvc.perform(post("/api/stage9/change-requests")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "CHANGE_REQUEST_CREATE")
            .header(RequestActorResolver.ACTOR_HEADER, trustedActor.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceType\":\"DRAFT_QUOTE\",\"sourceId\":\"" + sourceId + "\",\"requestedAction\":\"CREATE_DRAFT_QUOTE\",\"requestPayloadJson\":\"{\\\"simulateFailure\\\":true}\",\"actorId\":\"" + spoofActor + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetSystem").value("DEMO_ERP"));

    // Wave 01H Category C: actor comes from the trusted header, and the client-supplied
    // requestPayloadJson (here an attempted simulateFailure injection) is NOT forwarded — the backend
    // builds the payload (null -> neutral default), so the client cannot steer demo execution.
    ArgumentCaptor<UUID> actorCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(changeRequestService).createStage9DemoChangeRequest(eq("DRAFT_QUOTE"), eq(sourceId), eq("CREATE_DRAFT_QUOTE"), isNull(), actorCaptor.capture());
    assertThat(actorCaptor.getValue()).isEqualTo(trustedActor).isNotEqualTo(spoofActor);
  }

  // OP-CAP-32: reject carries business intent only (reason). A body-supplied actorId is backend-owned
  // authority and must be ignored — the service is called with the reason only and never an actor.
  @Test
  void connectorRejectIgnoresBodyActorIdAndUsesReasonOnly() throws Exception {
    UUID requestId = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();
    Instant now = Instant.parse("2026-05-27T00:00:00Z");
    ChangeRequest changeRequest = new ChangeRequest(UUID.randomUUID(), "DEMO_ERP", "DRAFT_QUOTE", "CREATE_DRAFT_QUOTE", "DRAFT_QUOTE", UUID.randomUUID(), "{}", "key", null, now);
    when(changeRequestService.rejectChangeRequest(eq(requestId), any())).thenReturn(changeRequest);

    mockMvc.perform(post("/api/stage9/change-requests/" + requestId + "/reject")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "CHANGE_REQUEST_REJECT")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"actorId\":\"" + spoofActor + "\",\"reason\":\"not authorised\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
    verify(changeRequestService).rejectChangeRequest(eq(requestId), reasonCaptor.capture());
    assertThat(reasonCaptor.getValue()).isEqualTo("not authorised").doesNotContain(spoofActor.toString());
  }

  // OP-CAP-32: cancel carries business intent only (reason). A body-supplied actorId must be ignored.
  @Test
  void connectorCancelIgnoresBodyActorIdAndUsesReasonOnly() throws Exception {
    UUID requestId = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();
    Instant now = Instant.parse("2026-05-27T00:00:00Z");
    ChangeRequest changeRequest = new ChangeRequest(UUID.randomUUID(), "DEMO_ERP", "DRAFT_QUOTE", "CREATE_DRAFT_QUOTE", "DRAFT_QUOTE", UUID.randomUUID(), "{}", "key", null, now);
    when(changeRequestService.cancelStage9DemoChangeRequest(eq(requestId), any())).thenReturn(changeRequest);

    mockMvc.perform(post("/api/stage9/change-requests/" + requestId + "/cancel")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "CHANGE_REQUEST_REJECT")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"actorId\":\"" + spoofActor + "\",\"reason\":\"operator cancelled\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
    verify(changeRequestService).cancelStage9DemoChangeRequest(eq(requestId), reasonCaptor.capture());
    assertThat(reasonCaptor.getValue()).isEqualTo("operator cancelled").doesNotContain(spoofActor.toString());
  }

  @Test
  void changeRequestListWithoutPermissionIsForbiddenBeforeServiceInvocationAndDoesNotLeakPayloads() throws Exception {
    MvcResult result = mockMvc.perform(get("/api/stage9/change-requests")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "AUTHENTICATED_PROBE"))
        .andExpect(status().isForbidden())
        .andReturn();

    assertForbiddenResponseDoesNotLeakSensitiveDetails(result);
    verifyNoInteractions(changeRequestService);
  }

  @Test
  void changeRequestDetailWithoutPermissionIsForbiddenBeforeServiceInvocationAndDoesNotLeakPayloads() throws Exception {
    UUID requestId = UUID.randomUUID();

    MvcResult result = mockMvc.perform(get("/api/stage9/change-requests/" + requestId)
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "AUTHENTICATED_PROBE"))
        .andExpect(status().isForbidden())
        .andReturn();

    assertForbiddenResponseDoesNotLeakSensitiveDetails(result);
    verifyNoInteractions(changeRequestService);
  }

  @Test
  void executionSafetyWithoutPermissionIsForbiddenBeforeServiceInvocationAndDoesNotLeakPayloads() throws Exception {
    UUID requestId = UUID.randomUUID();

    MvcResult result = mockMvc.perform(get("/api/stage9/change-requests/" + requestId + "/execution-safety")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "AUTHENTICATED_PROBE"))
        .andExpect(status().isForbidden())
        .andReturn();

    assertForbiddenResponseDoesNotLeakSensitiveDetails(result);
    verifyNoInteractions(safetyService);
  }

  @Test
  void executionSafetyWithAdminSettingsReadIsForbiddenBeforeServiceInvocation() throws Exception {
    UUID requestId = UUID.randomUUID();

    MvcResult result = mockMvc.perform(get("/api/stage9/change-requests/" + requestId + "/execution-safety")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "ADMIN_SETTINGS_READ"))
        .andExpect(status().isForbidden())
        .andReturn();

    assertForbiddenResponseDoesNotLeakSensitiveDetails(result);
    verifyNoInteractions(safetyService);
  }

  @Test
  void approveWithoutPermissionIsForbiddenBeforeServiceInvocationAndDoesNotLeakPayloads() throws Exception {
    UUID requestId = UUID.randomUUID();

    MvcResult result = mockMvc.perform(post("/api/stage9/change-requests/" + requestId + "/approve")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "AUTHENTICATED_PROBE")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"actorId\":\"00000000-0000-0000-0000-000000000001\",\"requestPayloadJson\":\"SECRET-PAYLOAD\"}"))
        .andExpect(status().isForbidden())
        .andReturn();

    assertForbiddenResponseDoesNotLeakSensitiveDetails(result);
    verify(changeRequestService, never()).approveChangeRequest(any(), any());
  }

  @Test
  void executeWithReadPermissionIsForbiddenBeforeServiceInvocation() throws Exception {
    UUID requestId = UUID.randomUUID();

    MvcResult result = mockMvc.perform(post("/api/stage9/change-requests/" + requestId + "/execute")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "CHANGE_REQUEST_READ"))
        .andExpect(status().isForbidden())
        .andReturn();

    assertForbiddenResponseDoesNotLeakSensitiveDetails(result);
    verify(changeRequestService, never()).executeStage9DemoChangeRequest(any());
  }

  @Test
  void retryWithReadPermissionIsForbiddenBeforeServiceInvocation() throws Exception {
    UUID requestId = UUID.randomUUID();

    MvcResult result = mockMvc.perform(post("/api/stage9/change-requests/" + requestId + "/retry")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "CHANGE_REQUEST_READ"))
        .andExpect(status().isForbidden())
        .andReturn();

    assertForbiddenResponseDoesNotLeakSensitiveDetails(result);
    verify(changeRequestService, never()).retryStage9DemoChangeRequest(any());
  }

  private void assertForbiddenResponseDoesNotLeakSensitiveDetails(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString();
    assertThat(body)
        .doesNotContain("SECRET-PAYLOAD")
        .doesNotContain("requestPayloadJson")
        .doesNotContain("ChangeRequestService")
        .doesNotContain("ConnectorExecutionSafetyService")
        .doesNotContain("java.lang")
        .doesNotContain("org.springframework")
        .doesNotContain("stackTrace");
  }
}
