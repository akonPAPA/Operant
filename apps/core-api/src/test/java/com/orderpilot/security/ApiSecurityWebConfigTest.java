package com.orderpilot.security;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.rest.HealthController;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = {
    HealthController.class,
    ApiSecurityWebConfigTest.BusinessProbeController.class
})
@Import({
    CoreConfiguration.class,
    ApiSecurityWebConfig.class,
    ApiRouteSecurityPolicy.class,
    ApiPermissionGuard.class,
    ApiSecurityWebConfigTest.BusinessProbeController.class
})
class ApiSecurityWebConfigTest {
  @Autowired private MockMvc mockMvc;

  @Test
  void publicHealthRouteIsAllowedWithoutAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void protectedBusinessRouteRejectsMissingAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/operator-review/security-baseline/business"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        .andExpect(jsonPath("$.message").value("Authentication required"))
        .andExpect(content().string(not(containsString("Exception"))));
  }

  @Test
  void protectedBusinessRouteAllowsCurrentGatewayAuthenticatedRequest() throws Exception {
    mockMvc.perform(get("/api/v1/operator-review/security-baseline/business")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "REVIEW_READ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("protected"));
  }

  @Test
  void unknownApiRouteIsNotPublic() throws Exception {
    mockMvc.perform(get("/api/v1/security-probe"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void csrfIsIgnoredOnlyAsStatelessApiPostureAndDoesNotPermitUnauthenticatedMutation() throws Exception {
    mockMvc.perform(post("/api/v1/operator-review/security-baseline/business")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(post("/api/v1/operator-review/security-baseline/business")
            .header(ApiPermissionGuard.PERMISSIONS_HEADER, "REVIEW_ACTION")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("mutated"));
  }

  @RestController
  @RequestMapping("/api/v1/operator-review/security-baseline/business")
  static class BusinessProbeController {
    @GetMapping
    java.util.Map<String, String> read() {
      return java.util.Map.of("status", "protected");
    }

    @PostMapping
    java.util.Map<String, String> mutate() {
      return java.util.Map.of("status", "mutated");
    }
  }
}
