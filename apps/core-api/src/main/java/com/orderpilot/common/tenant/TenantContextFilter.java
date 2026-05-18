package com.orderpilot.common.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TenantContextFilter extends OncePerRequestFilter {
  private final String tenantHeaderName;

  public TenantContextFilter(@Value("${orderpilot.tenant.header-name:X-Tenant-Id}") String tenantHeaderName) {
    this.tenantHeaderName = tenantHeaderName;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      String header = request.getHeader(tenantHeaderName);
      if (header != null && !header.isBlank()) {
        TenantContext.setTenantId(UUID.fromString(header));
      }
      filterChain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
  }
}