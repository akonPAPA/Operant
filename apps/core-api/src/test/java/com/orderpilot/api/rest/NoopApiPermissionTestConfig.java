package com.orderpilot.api.rest;

import com.orderpilot.security.ApiPermission;
import com.orderpilot.security.ApiPermissionGuard;
import com.orderpilot.security.ApiRouteSecurityPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

@TestConfiguration
class NoopApiPermissionTestConfig {
  @Bean
  @Order(0)
  SecurityFilterChain authenticatedControllerSliceSecurityFilterChain(HttpSecurity http) throws Exception {
    http
        .securityMatcher("/api/**", "/actuator/**", "/", "/favicon.ico")
        .csrf(csrf -> csrf.ignoringRequestMatchers(new AntPathRequestMatcher("/api/**")))
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .logout(logout -> logout.disable())
        .addFilterBefore(new ControllerSliceAuthenticationFilter(), AnonymousAuthenticationFilter.class)
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
    return http.build();
  }

  @Bean
  ApiPermissionGuard apiPermissionGuard() {
    return new ApiPermissionGuard() {
      @Override
      public void require(HttpServletRequest request, ApiPermission permission) {
        // Controller slice tests verify controller behavior.
        // Permission enforcement is covered by dedicated security tests.
      }
    };
  }

  @Bean
  ApiRouteSecurityPolicy apiRouteSecurityPolicy() {
    return new ApiRouteSecurityPolicy();
  }

  private static final class ControllerSliceAuthenticationFilter extends OncePerRequestFilter {
    private static final List<SimpleGrantedAuthority> AUTHORITIES =
        List.of(new SimpleGrantedAuthority("ORDERPILOT_CONTROLLER_CONTRACT"));

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
      if (SecurityContextHolder.getContext().getAuthentication() == null) {
        var authentication =
            new PreAuthenticatedAuthenticationToken("controller-slice-test", "N/A", AUTHORITIES);
        SecurityContextHolder.getContext().setAuthentication(authentication);
      }
      filterChain.doFilter(request, response);
    }
  }
}
