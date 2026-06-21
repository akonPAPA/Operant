package com.orderpilot.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.infrastructure.config.CoreConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = ApiSecurityCorsContractTest.CorsProbeController.class)
@Import({CoreConfiguration.class, ApiSecurityWebConfig.class, ApiPermissionGuard.class})
@TestPropertySource(properties = "orderpilot.security.cors.allowed-origins=http://localhost:3000,http://127.0.0.1:3000")
class ApiSecurityCorsContractTest {
  @Autowired private MockMvc mockMvc;

  @Test
  void configuredLocalOriginPreflightIsAllowedWithBoundedHeadersAndMethods() throws Exception {
    mockMvc.perform(options("/api/v1/security-baseline/cors")
            .header(HttpHeaders.ORIGIN, "http://localhost:3000")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type,X-Tenant-Id,Idempotency-Key"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,PATCH,DELETE,OPTIONS"))
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, X-Tenant-Id, Idempotency-Key"))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
  }

  @Test
  void unlistedOriginIsNotAllowed() throws Exception {
    mockMvc.perform(options("/api/v1/security-baseline/cors")
            .header(HttpHeaders.ORIGIN, "https://evil.example")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
  }

  @RestController
  static class CorsProbeController {
    @GetMapping("/api/v1/security-baseline/cors")
    java.util.Map<String, String> read() {
      return java.util.Map.of("status", "ok");
    }
  }
}
