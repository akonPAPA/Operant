package com.orderpilot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableWebSecurity
public class ApiSecurityWebConfig implements WebMvcConfigurer {
  static final String[] PERMISSION_INTERCEPTOR_PATHS = {
      "/api/v1/**",
      "/api/stage8/**",
      "/api/stage9/**"
  };
  static final String[] PUBLIC_GET_ROUTES = {
      "/",
      "/favicon.ico",
      "/actuator/health",
      "/actuator/info",
      "/api/v1/health"
  };
  static final String[] PUBLIC_POST_WEBHOOK_ROUTES = {
      "/api/v1/bot/telegram/webhook",
      "/api/v1/bot-runtime/telegram/webhook",
      "/api/v1/webhooks/email",
      "/api/v1/webhooks/telegram",
      "/api/v1/webhooks/telegram/*",
      "/api/v1/webhooks/whatsapp",
      "/api/v1/webhooks/whatsapp/*",
      "/api/v1/webhooks/channels/bot/telegram/*",
      "/api/v1/webhooks/channels/telegram/*",
      "/api/v1/webhooks/channels/whatsapp/*",
      "/api/v1/webhooks/channels/meta-messenger/*",
      "/api/v1/webhooks/channels/viber/*",
      "/api/v1/webhooks/channels/wechat/*",
      "/api/v1/channel-gateway/whatsapp/webhook"
  };
  private static final List<String> ALLOWED_METHODS =
      List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
  private static final List<String> ALLOWED_HEADERS = List.of(
      "Content-Type",
      "Authorization",
      "X-Tenant-Id",
      "X-Request-Id",
      "Idempotency-Key",
      ApiPermissionGuard.PERMISSIONS_HEADER,
      RequestActorResolver.ACTOR_HEADER,
      RequestActorResolver.SIGNATURE_HEADER,
      RequestActorResolver.TIMESTAMP_HEADER);

  private final ObjectProvider<ApiPermissionInterceptor> interceptor;
  private final List<String> allowedOrigins;
  private final boolean gatewayHeaderAuthEnabled;

  public ApiSecurityWebConfig(
      ObjectProvider<ApiPermissionInterceptor> interceptor,
      @Value("${orderpilot.security.cors.allowed-origins:}") String allowedOrigins,
      @Value("${orderpilot.security.gateway-header-auth.enabled:false}") boolean gatewayHeaderAuthEnabled) {
    this.interceptor = interceptor;
    this.allowedOrigins = parseCsv(allowedOrigins);
    this.gatewayHeaderAuthEnabled = gatewayHeaderAuthEnabled;
  }

  @Bean
  SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
    http
        .securityMatcher(new OrRequestMatcher(
            new AntPathRequestMatcher("/api/**"),
            new AntPathRequestMatcher("/actuator/**"),
            new AntPathRequestMatcher("/"),
            new AntPathRequestMatcher("/favicon.ico")))
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        // The API is stateless and uses gateway/auth headers rather than cookies; CSRF is ignored only
        // for API routes and does not make any business route public.
        .csrf(csrf -> csrf.ignoringRequestMatchers(new AntPathRequestMatcher("/api/**")))
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .logout(logout -> logout.disable())
        .addFilterBefore(new ApiHeaderAuthenticationFilter(gatewayHeaderAuthEnabled), AnonymousAuthenticationFilter.class)
        .exceptionHandling(exceptions -> exceptions
            .authenticationEntryPoint((request, response, ex) ->
                writeSecurityError(objectMapper, request, response, HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED", "Authentication required"))
            .accessDeniedHandler((request, response, ex) ->
                writeSecurityError(objectMapper, request, response, HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED", "Access denied")))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.GET, PUBLIC_GET_ROUTES).permitAll()
            .requestMatchers(HttpMethod.POST, PUBLIC_POST_WEBHOOK_ROUTES).permitAll()
            .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
            .requestMatchers("/api/**").authenticated()
            .requestMatchers("/actuator/**").denyAll()
            .anyRequest().denyAll());
    return http.build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(allowedOrigins);
    config.setAllowedMethods(ALLOWED_METHODS);
    config.setAllowedHeaders(ALLOWED_HEADERS);
    config.setAllowCredentials(false);
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    ApiPermissionInterceptor resolved = interceptor.getIfAvailable();
    if (resolved != null) {
      registry.addInterceptor(resolved).addPathPatterns(PERMISSION_INTERCEPTOR_PATHS);
    }
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    if (allowedOrigins.isEmpty()) {
      return;
    }
    registry.addMapping("/api/**")
        .allowedOrigins(allowedOrigins.toArray(String[]::new))
        .allowedMethods(ALLOWED_METHODS.toArray(String[]::new))
        .allowedHeaders(ALLOWED_HEADERS.toArray(String[]::new))
        .allowCredentials(false)
        .maxAge(3600);
  }

  private static List<String> parseCsv(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(origin -> !origin.isBlank())
        .toList();
  }

  private static void writeSecurityError(
      ObjectMapper objectMapper,
      HttpServletRequest request,
      HttpServletResponse response,
      HttpStatus status,
      String code,
      String message) throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(response.getWriter(), Map.of(
        "code", code,
        "message", message,
        "status", status.value(),
        "path", request.getRequestURI()));
  }

  private static final class ApiHeaderAuthenticationFilter extends OncePerRequestFilter {
    private final boolean enabled;

    private ApiHeaderAuthenticationFilter(boolean enabled) {
      this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
      String permissions = request.getHeader(ApiPermissionGuard.PERMISSIONS_HEADER);
      if (enabled
          && permissions != null && !permissions.isBlank()
          && SecurityContextHolder.getContext().getAuthentication() == null) {
        var authorities = Arrays.stream(permissions.split(","))
            .map(String::trim)
            .filter(permission -> !permission.isBlank())
            .map(permission -> new SimpleGrantedAuthority("ORDERPILOT_" + permission))
            .toList();
        var authentication = new PreAuthenticatedAuthenticationToken("orderpilot-gateway", "N/A", authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
      }
      filterChain.doFilter(request, response);
    }
  }
}
