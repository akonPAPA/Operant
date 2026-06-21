package com.orderpilot.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = ApiSecurityForgedHeaderTest.ForgedHeaderProbeController.class)
@Import({CoreConfiguration.class, ApiSecurityWebConfig.class, ApiPermissionGuard.class})
@TestPropertySource(properties = "orderpilot.security.gateway-header-auth.enabled=false")
class ApiSecurityForgedHeaderTest {
  @Autowired private MockMvc mockMvc;

  @Test
  void forgedTenantAndActorHeadersDoNotAuthenticateBusinessApiRequest() throws Exception {
    mockMvc.perform(get("/api/v1/security-baseline/forged-headers")
            .header("X-Tenant-Id", UUID.randomUUID().toString())
            .header(RequestActorResolver.ACTOR_HEADER, UUID.randomUUID().toString())
            .header(RequestActorResolver.SIGNATURE_HEADER, "forged")
            .header(RequestActorResolver.TIMESTAMP_HEADER, "2026-06-21T00:00:00Z"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void forgedPermissionHeaderDoesNotAuthenticateBusinessApiRequestWhenGatewayHeaderAuthIsDisabled() throws Exception {
    mockMvc.perform(get("/api/v1/security-baseline/forged-headers")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "REVIEW_READ"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @RestController
  static class ForgedHeaderProbeController {
    @GetMapping("/api/v1/security-baseline/forged-headers")
    java.util.Map<String, String> read() {
      return java.util.Map.of("status", "protected");
    }
  }
}
