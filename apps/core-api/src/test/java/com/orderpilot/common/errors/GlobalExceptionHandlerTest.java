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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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

  @RestController
  static class FailingController {
    @GetMapping("/test/failure")
    String failure() {
      throw new RuntimeException("database password leaked from internal stack");
    }
  }
}
