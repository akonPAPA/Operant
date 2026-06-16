package com.orderpilot.common.errors;

import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Bean;

import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.application.services.runtime.RuntimeFeatureNotAvailableException;
import com.orderpilot.application.services.runtime.RuntimeGuardDecision;
import com.orderpilot.application.services.runtime.RuntimeGuardReasonCodes;
import com.orderpilot.application.services.runtime.RuntimeOperationType;
import com.orderpilot.application.services.runtime.RuntimeRateLimitedException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.FailingController.class)
@Import(GlobalExceptionHandlerTest.TestSecurityConfig.class)
class GlobalExceptionHandlerTest {
  @Autowired
  private MockMvc mockMvc;

  @TestConfiguration
  static class TestSecurityConfig {
    @Bean
    Clock clock() {
      return Clock.fixed(
        Instant.parse("2026-01-01T00:00:00Z"),
        ZoneOffset.UTC
      );
    }
    @Bean
    ApiPermissionGuard apiPermissionGuard() {
      return new ApiPermissionGuard() {
        @Override
        public void require(HttpServletRequest request, ApiPermission permission) {
          return;
        }
      };
    }
  }

  @Test
  void unexpectedErrorsReturnSafeResponseWithoutInternalDetails() throws Exception {
    mockMvc.perform(get("/test/failure"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code", is("INTERNAL_ERROR")))
        .andExpect(jsonPath("$.message", is("Unexpected server error")))
        .andExpect(jsonPath("$.message", not(containsString("database password"))))
        .andExpect(jsonPath("$.status", is(500)))
        .andExpect(jsonPath("$.path", is("/test/failure")));
  }

  // OP-CAP-16D: runtime feature entitlement denial maps to a stable 403 RUNTIME_FEATURE_NOT_AVAILABLE
  // via the shared RuntimeLimitException handler (tested directly to avoid web-slice handler-lookup
  // quirks; DispatcherServlet routing of typed exceptions to @ExceptionHandler is standard Spring).
  @Test
  void runtimeFeatureNotAvailableMapsTo403() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler(fixedClock());
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/extractions/runs/execute");

    var response =
        handler.handleRuntimeLimit(
            new RuntimeFeatureNotAvailableException(denied(403, RuntimeGuardReasonCodes.FEATURE_NOT_AVAILABLE, 0L)),
            request);

    assertThat(response.getStatusCode().value()).isEqualTo(403);
    assertThat(response.getBody().code()).isEqualTo("RUNTIME_FEATURE_NOT_AVAILABLE");
    assertThat(response.getHeaders().getFirst("Retry-After")).isNull();
  }

  // OP-CAP-16C/16D: runtime rate limit denial maps to 429 with a Retry-After header.
  @Test
  void runtimeRateLimitedMapsTo429WithRetryAfter() {
    GlobalExceptionHandler handler = new GlobalExceptionHandler(fixedClock());
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/extractions/runs/execute");

    var response =
        handler.handleRuntimeLimit(
            new RuntimeRateLimitedException(denied(429, RuntimeGuardReasonCodes.RATE_LIMIT_EXCEEDED, 30L)),
            request);

    assertThat(response.getStatusCode().value()).isEqualTo(429);
    assertThat(response.getBody().code()).isEqualTo("RUNTIME_RATE_LIMITED");
    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
  }

  private static Clock fixedClock() {
    return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
  }

  private static RuntimeGuardDecision denied(int httpHint, String reasonCode, long retryAfter) {
    return new RuntimeGuardDecision(
        false, httpHint, reasonCode, RuntimeOperationType.AI_DOCUMENT_EXTRACTION,
        null, 1L, null, 0L, null, retryAfter, null);
  }

  @RestController
  static class FailingController {
    @GetMapping("/test/failure")
    String failure() {
      throw new RuntimeException("database password leaked from internal stack");
    }
  }
}
