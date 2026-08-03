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

/**
 * Establishes tenant context from the configured tenant header for authenticated/operator routes.
 *
 * <p>Public provider webhook routes must <b>not</b> trust {@code X-Tenant-Id}. Those routes resolve
 * tenant from a server-owned {@code ChannelConnection} after connection selection and verification.
 */
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
      if (!isPublicWebhookIntake(request.getRequestURI())) {
        String header = request.getHeader(tenantHeaderName);
        if (header != null && !header.isBlank()) {
          TenantContext.setTenantId(UUID.fromString(header));
        }
      }
      filterChain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
  }

  static boolean isPublicWebhookIntake(String requestUri) {
    if (requestUri == null || requestUri.isBlank()) {
      return false;
    }
    String path = requestUri;
    int query = path.indexOf('?');
    if (query >= 0) {
      path = path.substring(0, query);
    }
    // Only connection-scoped / gateway provider intake forbids trusting X-Tenant-Id. Legacy local-only
    // stub routes still may carry a demo tenant header under LegacyWebhookIngressGuard.
    return path.startsWith("/api/v1/webhooks/channels/")
        || path.startsWith("/api/v1/channel-gateway/whatsapp/webhook");
  }
}
