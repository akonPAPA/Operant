package com.orderpilot.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ApiSecurityWebConfig implements WebMvcConfigurer {
  private final ObjectProvider<ApiPermissionInterceptor> interceptor;

  public ApiSecurityWebConfig(ObjectProvider<ApiPermissionInterceptor> interceptor) {
    this.interceptor = interceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    ApiPermissionInterceptor resolved = interceptor.getIfAvailable();
    if (resolved != null) {
      registry.addInterceptor(resolved).addPathPatterns("/api/v1/**");
    }
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/api/**")
        .allowedOrigins("http://localhost:3000", "http://127.0.0.1:3000")
        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        .allowedHeaders("Content-Type", "Authorization", "X-Tenant-Id", "X-Request-Id", "Idempotency-Key", "X-OrderPilot-Permissions", "X-OrderPilot-Actor-Id", "X-OrderPilot-Actor-Signature", "X-OrderPilot-Actor-Timestamp")
        .maxAge(3600);
  }
}
