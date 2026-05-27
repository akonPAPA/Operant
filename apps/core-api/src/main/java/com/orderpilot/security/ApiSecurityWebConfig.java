package com.orderpilot.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
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
}
