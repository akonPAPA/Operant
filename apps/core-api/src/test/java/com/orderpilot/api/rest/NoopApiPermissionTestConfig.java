package com.orderpilot.api.rest;

import com.orderpilot.security.ApiPermission;
import com.orderpilot.security.ApiPermissionGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@TestConfiguration
class NoopApiPermissionTestConfig {
  @Bean
  @Order(0)
  SecurityFilterChain noopControllerSliceSecurityFilterChain(HttpSecurity http) throws Exception {
    http
        .securityMatcher("/api/**", "/actuator/**", "/", "/favicon.ico")
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }

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
