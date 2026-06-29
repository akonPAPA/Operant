package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.application.services.integration.ChangeRequestService;
import com.orderpilot.application.services.workspace.QuoteExternalWritePreparationService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.domain.integration.ChangeRequest;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
import com.orderpilot.security.RequestActorResolver;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// OP-CAP-42C: request-body authority / mass-assignment proof for ChangeRequest creation.
//
// ChangeRequest is an external-write-adjacent aggregate. Its lifecycle authority — tenant, creator,
// validation status, approval status, execution status — is backend-owned: the entity constructor
// hard-codes PENDING_VALIDATION / PENDING_APPROVAL / EXECUTION_DISABLED and the creator is resolved
// from the trusted actor context (RequestActorResolver), never from the body.
//
// ChangeRequestControllerActorAuthorityTest already proves the *createdBy* actor cannot be forged.
// This test closes the adjacent gap: a malicious create body that smuggles workflow-state authority
// fields (approvalStatus / executionStatus / validationStatus / externalExecution / status /
// riskLevel / margin / stock / tenantId / connectorId / auditEventId / internalId / entityId) must
// NOT pre-approve, pre-execute, pre-validate, or otherwise short-circuit the gate. The public DTO has
// no such fields, so they are unmapped on deserialization; this test proves the *consequence*: the
// command the service receives carries only business intent, and the response reflects backend-owned
// state — not the attacker's claimed authority. (Unknown JSON fields are silently ignored by default;
// this is an ignored-and-inert proof, not an explicit fail-on-unknown rejection.)
@WebMvcTest(ChangeRequestController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, ApiSecurityWebConfig.class, ApiPermissionInterceptor.class, ApiPermissionGuard.class, RequestActorResolver.class})
class ChangeRequestCreateMassAssignmentTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private ChangeRequestService service;
  @MockBean private QuoteExternalWritePreparationService externalWritePreparationService;

  @Test
  void maliciousAuthorityAndStateBodyFieldsCannotPreApproveOrPreExecuteChangeRequest() throws Exception {
    UUID sourceId = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();
    UUID trustedTenant = UUID.randomUUID();
    UUID forgedTenant = UUID.randomUUID();
    Instant now = Instant.parse("2026-06-22T00:00:00Z");

    // The mock returns a ChangeRequest built through the REAL constructor, so its state is exactly
    // what the backend would persist for a fresh create: PENDING_VALIDATION / PENDING_APPROVAL /
    // EXECUTION_DISABLED. If body authority leaked into the command it would have to change these.
    ChangeRequest persisted = new ChangeRequest(UUID.randomUUID(), "ONEC", "ORDER", "CREATE_ORDER", "QUOTE", sourceId, "{}", "key", trustedActor, now);
    when(service.createChangeRequest(anyString(), anyString(), anyString(), anyString(), any(), nullable(String.class), nullable(String.class), any())).thenReturn(persisted);

    String maliciousBody = "{"
        + "\"targetSystem\":\"ONEC\",\"targetEntity\":\"ORDER\",\"requestedAction\":\"CREATE_ORDER\","
        + "\"sourceType\":\"QUOTE\",\"sourceId\":\"" + sourceId + "\",\"requestPayloadJson\":\"SMUGGLED-PAYLOAD\",\"idempotencyKey\":\"body-smuggled-key\","
        // forged authority / system / calculated fields a hostile client might try to mass-assign:
        + "\"tenantId\":\"" + forgedTenant + "\",\"createdByUserId\":\"" + spoofActor + "\",\"approvedByUserId\":\"" + spoofActor + "\","
        + "\"status\":\"APPROVED\",\"validationStatus\":\"VALIDATED\",\"approvalStatus\":\"APPROVED\",\"executionStatus\":\"EXECUTED\","
        + "\"externalExecution\":true,\"externalWriteAuthority\":\"GRANTED\",\"riskLevel\":\"LOW\",\"margin\":99,\"stock\":999,"
        + "\"connectorId\":\"" + UUID.randomUUID() + "\",\"connectionId\":\"" + UUID.randomUUID() + "\","
        + "\"auditEventId\":\"" + UUID.randomUUID() + "\",\"internalId\":\"" + UUID.randomUUID() + "\",\"entityId\":\"" + UUID.randomUUID() + "\""
        + "}";

    mockMvc.perform(post("/api/v1/change-requests")
            .header("X-Tenant-Id", trustedTenant.toString())
            .header(RequestActorResolver.ACTOR_HEADER, trustedActor.toString())
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "CHANGE_REQUEST_CREATE")
            .header("Idempotency-Key", "trusted-header-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content(maliciousBody))
        .andExpect(status().isOk())
        // Backend-owned lifecycle state — NOT the attacker's claimed APPROVED/EXECUTED/VALIDATED.
        .andExpect(jsonPath("$.validationStatus").value("PENDING_VALIDATION"))
        .andExpect(jsonPath("$.approvalStatus").value("PENDING_APPROVAL"))
        .andExpect(jsonPath("$.executionStatus").value("EXECUTION_DISABLED"))
        // The forged authority/internal fields are neither honoured nor echoed back on the response.
        .andExpect(jsonPath("$.tenantId").doesNotExist())
        .andExpect(jsonPath("$.createdByUserId").doesNotExist())
        .andExpect(jsonPath("$.approvedByUserId").doesNotExist())
        .andExpect(jsonPath("$.externalExecution").doesNotExist())
        .andExpect(jsonPath("$.externalWriteAuthority").doesNotExist())
        .andExpect(jsonPath("$.riskLevel").doesNotExist())
        .andExpect(jsonPath("$.connectorId").doesNotExist());

    // Wave 01H Category C: the command the service receives carries only business intent. The
    // external-write payload is backend-owned (null -> neutral default), so the body's SMUGGLED-PAYLOAD
    // never reaches lower-layer state; idempotency is the trusted Idempotency-Key header value, never
    // the body-smuggled key; and createdBy is the trusted actor, never the spoofed one.
    ArgumentCaptor<UUID> sourceCaptor = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<String> idempotencyCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<UUID> createdByCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(service).createChangeRequest(
        org.mockito.ArgumentMatchers.eq("ONEC"),
        org.mockito.ArgumentMatchers.eq("ORDER"),
        org.mockito.ArgumentMatchers.eq("CREATE_ORDER"),
        org.mockito.ArgumentMatchers.eq("QUOTE"),
        sourceCaptor.capture(),
        isNull(),
        idempotencyCaptor.capture(),
        createdByCaptor.capture());
    assertThat(sourceCaptor.getValue()).isEqualTo(sourceId);
    assertThat(idempotencyCaptor.getValue()).isEqualTo("trusted-header-key").isNotEqualTo("body-smuggled-key");
    assertThat(createdByCaptor.getValue()).isEqualTo(trustedActor).isNotEqualTo(spoofActor);
  }
}
