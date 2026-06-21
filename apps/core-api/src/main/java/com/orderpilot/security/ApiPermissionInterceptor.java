package com.orderpilot.security;

import com.orderpilot.security.policy.TenantPolicyException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ApiPermissionInterceptor implements HandlerInterceptor {
  private final ApiPermissionGuard guard;
  private final ApiRouteSecurityPolicy policy;

  public ApiPermissionInterceptor(ApiPermissionGuard guard) {
    this(guard, new ApiRouteSecurityPolicy());
  }

  public ApiPermissionInterceptor(ApiPermissionGuard guard, ApiRouteSecurityPolicy policy) {
    this.guard = guard;
    this.policy = policy;
  }

  @Autowired
  public ApiPermissionInterceptor(ApiPermissionGuard guard, ObjectProvider<ApiRouteSecurityPolicy> policy) {
    this(guard, policy.getIfAvailable(ApiRouteSecurityPolicy::new));
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (HttpMethod.OPTIONS.matches(request.getMethod())) {
      return true;
    }
    var decision = policy.classify(request.getMethod(), request.getRequestURI())
        .orElseThrow(() -> new TenantPolicyException(
            "Unclassified API route " + request.getMethod() + " " + request.getRequestURI()));
    if (!decision.isPublic()) {
      guard.require(request, decision.requiredPermission());
    }
    return true;
  }

  ApiPermission requiredPermissionFor(String method, String path) {
    if (HttpMethod.OPTIONS.matches(method)) {
      return null;
    }
    return policy.requiredPermissionFor(method, path).orElse(null);
  }
}
