package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.Stage11EDtos.ChangeRequestCancelCommand;
import com.orderpilot.api.dto.Stage11EDtos.QuoteHandoffResponse;
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

// OP-CAP-17F: the change-request creator (createdBy) is an authority field. It must be taken from the
// trusted actor context, never from the request body, so a caller cannot forge who originated an
// external-write ChangeRequest.
@WebMvcTest(ChangeRequestController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, ApiSecurityWebConfig.class, ApiPermissionInterceptor.class, ApiPermissionGuard.class, RequestActorResolver.class})
class ChangeRequestControllerActorAuthorityTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private ChangeRequestService service;
  @MockBean private QuoteExternalWritePreparationService externalWritePreparationService;

  @Test
  void createUsesTrustedActorNotBodyCreatedByUserId() throws Exception {
    UUID sourceId = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();
    Instant now = Instant.parse("2026-05-27T00:00:00Z");
    ChangeRequest changeRequest = new ChangeRequest(UUID.randomUUID(), "ONEC", "ORDER", "CREATE_ORDER", "QUOTE", sourceId, "{}", "key", trustedActor, now);
    when(service.createChangeRequest(anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any())).thenReturn(changeRequest);

    mockMvc.perform(post("/api/v1/change-requests")
            .header(RequestActorResolver.ACTOR_HEADER, trustedActor.toString())
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "CHANGE_REQUEST_CREATE")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetSystem\":\"ONEC\",\"targetEntity\":\"ORDER\",\"requestedAction\":\"CREATE_ORDER\",\"sourceType\":\"QUOTE\",\"sourceId\":\"" + sourceId + "\",\"requestPayloadJson\":\"{}\",\"idempotencyKey\":\"key\",\"createdByUserId\":\"" + spoofActor + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetSystem").value("ONEC"));

    ArgumentCaptor<UUID> createdByCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(service).createChangeRequest(eq("ONEC"), eq("ORDER"), eq("CREATE_ORDER"), eq("QUOTE"), eq(sourceId), eq("{}"), eq("key"), createdByCaptor.capture());
    assertThat(createdByCaptor.getValue()).isEqualTo(trustedActor).isNotEqualTo(spoofActor);
  }

  @Test
  void validCreateWithoutActorHeaderUsesSafeSystemActorFallback() throws Exception {
    UUID sourceId = UUID.randomUUID();
    Instant now = Instant.parse("2026-05-27T00:00:00Z");
    ChangeRequest changeRequest = new ChangeRequest(UUID.randomUUID(), "ONEC", "ORDER", "CREATE_ORDER", "QUOTE", sourceId, "{}", "key", RequestActorResolver.SYSTEM_ACTOR, now);
    when(service.createChangeRequest(anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any())).thenReturn(changeRequest);

    mockMvc.perform(post("/api/v1/change-requests")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "CHANGE_REQUEST_CREATE")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetSystem\":\"ONEC\",\"targetEntity\":\"ORDER\",\"requestedAction\":\"CREATE_ORDER\",\"sourceType\":\"QUOTE\",\"sourceId\":\"" + sourceId + "\",\"requestPayloadJson\":\"{}\",\"idempotencyKey\":\"key\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<UUID> createdByCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(service).createChangeRequest(eq("ONEC"), eq("ORDER"), eq("CREATE_ORDER"), eq("QUOTE"), eq(sourceId), eq("{}"), eq("key"), createdByCaptor.capture());
    // Unsigned local/dev/test mode: a missing actor header resolves to the stable SYSTEM_ACTOR
    // sentinel (existing safe behavior), never null-from-body.
    assertThat(createdByCaptor.getValue()).isEqualTo(RequestActorResolver.SYSTEM_ACTOR);
  }

  @Test
  void malformedActorHeaderIsRejectedWithBadRequest() throws Exception {
    UUID sourceId = UUID.randomUUID();
    mockMvc.perform(post("/api/v1/change-requests")
            .header(RequestActorResolver.ACTOR_HEADER, "not-a-uuid")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "CHANGE_REQUEST_CREATE")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetSystem\":\"ONEC\",\"targetEntity\":\"ORDER\",\"requestedAction\":\"CREATE_ORDER\",\"sourceType\":\"QUOTE\",\"sourceId\":\"" + sourceId + "\",\"requestPayloadJson\":\"{}\",\"idempotencyKey\":\"key\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  @Test
  void cancelUsesTrustedActorNotBodyActorId() throws Exception {
    UUID tenantId = UUID.randomUUID();
    UUID changeRequestId = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();
    when(externalWritePreparationService.cancel(eq(changeRequestId), any()))
        .thenReturn(new QuoteHandoffResponse(
            quoteId,
            "APPROVED",
            "CANCELLED",
            java.util.List.of(),
            false,
            changeRequestId,
            false,
            java.util.List.of()));

    mockMvc.perform(post("/api/v1/change-requests/{id}/cancel", changeRequestId)
            .header("X-Tenant-Id", tenantId)
            .header(RequestActorResolver.ACTOR_HEADER, trustedActor.toString())
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "CHANGE_REQUEST_REJECT")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"actorId\":\"" + spoofActor + "\",\"reason\":\"duplicate\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<ChangeRequestCancelCommand> command =
        ArgumentCaptor.forClass(ChangeRequestCancelCommand.class);
    verify(externalWritePreparationService).cancel(eq(changeRequestId), command.capture());
    assertThat(command.getValue().actorId()).isEqualTo(trustedActor).isNotEqualTo(spoofActor);
    assertThat(command.getValue().reason()).isEqualTo("duplicate");
  }
}
