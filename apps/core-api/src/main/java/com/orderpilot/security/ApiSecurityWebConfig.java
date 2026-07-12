package com.orderpilot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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
@Import(GatewayHeaderReplayStoreConfiguration.class)
public class ApiSecurityWebConfig implements WebMvcConfigurer {
  // OP-CAP-44E: the permission interceptor is registered on the ENTIRE /api/** surface, not just the
  // currently-shipped /api/v1, /api/stage8, /api/stage9 prefixes. Registering only the known prefixes
  // left a fail-open class: any future /api/<other> route (e.g. a new /api/v2 or /api/internal group)
  // would be authenticated by Spring Security but never reach an authorization check. With /api/** the
  // interceptor runs on every API route and ApiRouteSecurityPolicy fails closed (TenantPolicyException)
  // on any path it does not explicitly classify as public or protected. Known-public routes (health,
  // provider webhooks) are still classified public by the policy and pass through.
  static final String[] PERMISSION_INTERCEPTOR_PATHS = {
      "/api/**"
  };
  // Documents the protected API route groups currently shipped. /api/** above covers these plus any
  // future group; this list keeps the coverage test explicit about the known groups it must protect.
  static final String[] KNOWN_PROTECTED_ROUTE_GROUPS = {
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
  // OP-CAP-46C: public-with-token secure tracking link(s). Distinct from PUBLIC_GET_ROUTES (which is
  // strictly health/intentional) because these are business reads gated by an opaque, expiring,
  // tenant/journey-scoped token in the path (the sole credential) rather than a permission grant. The
  // route policy classifies them SECURE_TRACKING_LINK_PUBLIC_WITH_TOKEN; the service resolves all scope
  // from the token. Read-only — no external write, no order/ETA/milestone mutation.
  static final String[] PUBLIC_GET_SECURE_LINK_ROUTES = {
      "/api/v1/public/order-tracking/*"
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
      GatewayHeaderSignatureVerifier.TENANT_HEADER,
      "X-Request-Id",
      "Idempotency-Key",
      ApiPermissionGuard.PERMISSIONS_HEADER,
      RequestActorResolver.ACTOR_HEADER,
      RequestActorResolver.SIGNATURE_HEADER,
      RequestActorResolver.TIMESTAMP_HEADER,
      GatewayHeaderSignatureVerifier.TIMESTAMP_HEADER,
      GatewayHeaderSignatureVerifier.SIGNATURE_HEADER,
      GatewayHeaderSignatureVerifier.NONCE_HEADER,
      GatewayHeaderSignatureVerifier.VERSION_HEADER,
      GatewayHeaderSignatureVerifier.CONTENT_SHA256_HEADER);
  /** Absolute max body bytes for gateway signature content-hash verification (fail-closed). */
  private static final int GATEWAY_SIGNED_MAX_BODY_BYTES = 2 * 1024 * 1024;

  private final ObjectProvider<ApiPermissionInterceptor> interceptor;
  private final List<String> allowedOrigins;
  private final boolean gatewayHeaderAuthEnabled;
  private final boolean gatewayHeaderSignatureRequired;
  private final String gatewayHeaderSharedSecret;
  private final long gatewayHeaderClockSkewSeconds;
  private final Clock clock;

  public ApiSecurityWebConfig(
      ObjectProvider<ApiPermissionInterceptor> interceptor,
      @Value("${orderpilot.security.cors.allowed-origins:}") String allowedOrigins,
      @Value("${orderpilot.security.gateway-header-auth.enabled:false}") boolean gatewayHeaderAuthEnabled,
      @Value("${orderpilot.security.gateway-header-auth.signature-required:true}") boolean gatewayHeaderSignatureRequired,
      @Value("${orderpilot.security.gateway-header-auth.shared-secret:}") String gatewayHeaderSharedSecret,
      @Value("${orderpilot.security.gateway-header-auth.clock-skew-seconds:300}") long gatewayHeaderClockSkewSeconds,
      Clock clock) {
    this.interceptor = interceptor;
    this.allowedOrigins = parseCsv(allowedOrigins);
    this.gatewayHeaderAuthEnabled = gatewayHeaderAuthEnabled;
    this.gatewayHeaderSignatureRequired = gatewayHeaderSignatureRequired;
    this.gatewayHeaderSharedSecret = gatewayHeaderSharedSecret;
    this.gatewayHeaderClockSkewSeconds = gatewayHeaderClockSkewSeconds;
    this.clock = clock;
  }

  @Bean
  SecurityFilterChain apiSecurityFilterChain(
      HttpSecurity http,
      ObjectMapper objectMapper,
      GatewayHeaderReplayAdmissionStore replayAdmissionStore) throws Exception {
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
        .addFilterBefore(
            new ApiHeaderAuthenticationFilter(
                gatewayHeaderAuthEnabled,
                gatewayHeaderSignatureRequired,
                new GatewayHeaderSignatureVerifier(
                    gatewayHeaderSharedSecret,
                    gatewayHeaderClockSkewSeconds,
                    clock,
                    replayAdmissionStore)),
            AnonymousAuthenticationFilter.class)
        .exceptionHandling(exceptions -> exceptions
            .authenticationEntryPoint((request, response, ex) ->
                writeSecurityError(objectMapper, request, response, HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED", "Authentication required"))
            .accessDeniedHandler((request, response, ex) ->
                writeSecurityError(objectMapper, request, response, HttpStatus.FORBIDDEN,
                    "ACCESS_DENIED", "Access denied")))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.GET, PUBLIC_GET_ROUTES).permitAll()
            .requestMatchers(HttpMethod.GET, PUBLIC_GET_SECURE_LINK_ROUTES).permitAll()
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
    private final boolean signatureRequired;
    private final GatewayHeaderSignatureVerifier signatureVerifier;

    private ApiHeaderAuthenticationFilter(
        boolean enabled,
        boolean signatureRequired,
        GatewayHeaderSignatureVerifier signatureVerifier) {
      this.enabled = enabled;
      this.signatureRequired = signatureRequired;
      this.signatureVerifier = signatureVerifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
      String permissions = request.getHeader(ApiPermissionGuard.PERMISSIONS_HEADER);
      HttpServletRequest effectiveRequest = request;
      boolean authenticated = false;
      if (enabled && permissions != null && !permissions.isBlank()) {
        if (signatureRequired) {
          CachedBodyHttpServletRequest cached =
              CachedBodyHttpServletRequest.wrap(request, GATEWAY_SIGNED_MAX_BODY_BYTES);
          if (cached == null) {
            // Oversized / malformed Content-Length: do not authenticate; leave body consumed.
            filterChain.doFilter(request, response);
            return;
          }
          effectiveRequest = cached;
          authenticated = signatureVerifier.verify(cached);
        } else {
          authenticated = true;
        }
      }
      if (authenticated && SecurityContextHolder.getContext().getAuthentication() == null) {
        var authorities = Arrays.stream(permissions.split(","))
            .map(String::trim)
            .filter(permission -> !permission.isBlank())
            .map(permission -> new SimpleGrantedAuthority("ORDERPILOT_" + permission))
            .toList();
        var authentication = new PreAuthenticatedAuthenticationToken("orderpilot-gateway", "N/A", authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
      }
      filterChain.doFilter(effectiveRequest, response);
    }
  }
}
