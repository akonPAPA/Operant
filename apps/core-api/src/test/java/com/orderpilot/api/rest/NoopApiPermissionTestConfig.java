package com.orderpilot.api.rest;

import com.orderpilot.security.ApiPermission;
import com.orderpilot.security.ApiPermissionGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
class NoopApiPermissionTestConfig {
  @Bean
  ApiPermissionGuard apiPermissionGuard() {
    return new ApiPermissionGuard() {
      @Override
      public void require(HttpServletRequest request, ApiPermission permission) {
        // MVC slice tests verify controller behavior; permission policy has dedicated coverage.
      }
    };
  }
}
