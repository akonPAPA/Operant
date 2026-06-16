package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.RuntimeEntitlementAdminDtos.RuntimeEntitlementStatusResponse;
import com.orderpilot.api.dto.RuntimeEntitlementAdminDtos.TenantRuntimePlanResponse;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService.CreatePlanCommand;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.usage.TenantRuntimePlanCode;
import com.orderpilot.domain.usage.TenantRuntimePlanStatus;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermissionInterceptor;
import com.orderpilot.security.ApiSecurityWebConfig;
import com.orderpilot.security.RequestActorResolver;
import com.orderpilot.security.SignedActorVerifier;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OP-CAP-16K — runtime entitlement admin controller in <b>signed-actor mode</b> (signing secret
 * configured). A valid HMAC over {@code tenantId\nactorId\ntimestamp} is required for mutations;
 * missing/invalid/stale signatures are 401; the manage permission is still required; reads are
 * unaffected. The audit actor is the verified actor; the body cannot assert it.
 */
@WebMvcTest(RuntimeEntitlementAdminController.class)
@Import({CoreConfiguration.class, ApiSecurityWebConfig.class, ApiPermissionInterceptor.class, ApiPermissionGuard.class, RequestActorResolver.class})
@TestPropertySource(properties = "orderpilot.security.actor-signing-secret=stage16k-test-secret")
class RuntimeEntitlementAdminControllerSignedActorStage16KTest {
  private static final String SECRET = "stage16k-test-secret";
  private static final String MANAGE = "RUNTIME_ENTITLEMENT_MANAGE";
  private static final String READ = "RUNTIME_ENTITLEMENT_READ";
  private static final String PERM = "X-OrderPilot-Permissions";
  private static final String ACTOR = "X-OrderPilot-Actor-Id";
  private static final String SIG = "X-OrderPilot-Actor-Signature";
  private static final String TS = "X-OrderPilot-Actor-Timestamp";

  @Autowired private MockMvc mockMvc;
  @MockBean private RuntimeEntitlementAdminService service;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private TenantRuntimePlanResponse anyPlan() {
    Instant now = Instant.parse("2026-06-13T12:00:00Z");
    return new TenantRuntimePlanResponse(UUID.randomUUID(), UUID.randomUUID(), TenantRuntimePlanCode.PRO,
        TenantRuntimePlanStatus.ACTIVE, now, null, now, now, List.of());
  }

  private static String sign(UUID tenant, UUID actor, long ts) {
    return SignedActorVerifier.hmacHex(SECRET, tenant + "\n" + actor + "\n" + ts);
  }

  @Test
  void validSignedMutationAcceptedAndUsesVerifiedActor() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    long ts = Instant.now().getEpochSecond();
    TenantContext.setTenantId(tenant);
    when(service.createPlan(any())).thenReturn(anyPlan());

    mockMvc.perform(post("/api/v1/runtime/plans")
            .header(PERM, MANAGE).header(ACTOR, actor.toString())
            .header(SIG, sign(tenant, actor, ts)).header(TS, Long.toString(ts))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"planCode\":\"PRO\",\"status\":\"ACTIVE\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<CreatePlanCommand> captor = ArgumentCaptor.forClass(CreatePlanCommand.class);
    verify(service).createPlan(captor.capture());
    assertThat(captor.getValue().actorId()).isEqualTo(actor);
  }

  @Test
  void missingSignatureRejectedWhenSecretConfigured() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    TenantContext.setTenantId(tenant);

    mockMvc.perform(post("/api/v1/runtime/plans")
            .header(PERM, MANAGE).header(ACTOR, actor.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"planCode\":\"PRO\",\"status\":\"ACTIVE\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void invalidSignatureRejected() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    long ts = Instant.now().getEpochSecond();
    TenantContext.setTenantId(tenant);

    mockMvc.perform(post("/api/v1/runtime/plans")
            .header(PERM, MANAGE).header(ACTOR, actor.toString())
            .header(SIG, sign(tenant, actor, ts) + "ff").header(TS, Long.toString(ts))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"planCode\":\"PRO\",\"status\":\"ACTIVE\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void staleTimestampRejected() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    long ts = Instant.now().getEpochSecond() - 4000L;
    TenantContext.setTenantId(tenant);

    mockMvc.perform(post("/api/v1/runtime/plans")
            .header(PERM, MANAGE).header(ACTOR, actor.toString())
            .header(SIG, sign(tenant, actor, ts)).header(TS, Long.toString(ts))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"planCode\":\"PRO\",\"status\":\"ACTIVE\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void managePermissionStillRequiredEvenWithValidSignature() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    long ts = Instant.now().getEpochSecond();
    TenantContext.setTenantId(tenant);

    mockMvc.perform(post("/api/v1/runtime/plans")
            .header(PERM, READ).header(ACTOR, actor.toString())
            .header(SIG, sign(tenant, actor, ts)).header(TS, Long.toString(ts))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"planCode\":\"PRO\",\"status\":\"ACTIVE\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void readEndpointUnaffectedBySigningInSignedMode() throws Exception {
    UUID tenant = UUID.randomUUID();
    when(service.getCurrentRuntimeEntitlements())
        .thenReturn(new RuntimeEntitlementStatusResponse(tenant, "COMPATIBILITY_DEFAULT", null, List.of()));

    mockMvc.perform(get("/api/v1/runtime/entitlements").header(PERM, READ))
        .andExpect(status().isOk());
  }
}
