package com.orderpilot.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ApiPermissionInterceptor implements HandlerInterceptor {
  private final ApiPermissionGuard guard;
  private final Map<String, ApiPermission> readPrefixes = Map.ofEntries(
      Map.entry("/api/v1/analytics", ApiPermission.ANALYTICS_READ),
      Map.entry("/api/v1/analytics/commerce", ApiPermission.ANALYTICS_READ),
      Map.entry("/api/stage8/analytics", ApiPermission.ANALYTICS_READ),
      Map.entry("/api/stage8/reconciliation", ApiPermission.ANALYTICS_READ),
      Map.entry("/api/stage8/value", ApiPermission.ANALYTICS_READ),
      Map.entry("/api/stage9", ApiPermission.ADMIN_SETTINGS_READ),
      Map.entry("/api/v1/intake", ApiPermission.INTAKE_READ),
      Map.entry("/api/v1/webhooks/events", ApiPermission.INTAKE_READ),
      Map.entry("/api/v1/extractions", ApiPermission.EXTRACTION_READ),
      Map.entry("/api/v1/validations", ApiPermission.VALIDATION_READ),
      Map.entry("/api/v1/operator-review", ApiPermission.REVIEW_READ),
      Map.entry("/api/v1/validation-review", ApiPermission.REVIEW_READ),
      Map.entry("/api/v1/workspace/draft-quotes", ApiPermission.REVIEW_READ),
      Map.entry("/api/v1/workspace/draft-orders", ApiPermission.REVIEW_READ),
      Map.entry("/api/v1/workspace/products", ApiPermission.REVIEW_READ),
      Map.entry("/api/v1/quote-review", ApiPermission.REVIEW_READ),
      Map.entry("/api/v1/quotes", ApiPermission.QUOTE_READ),
      Map.entry("/api/v1/quote-transactions", ApiPermission.QUOTE_READ),
      Map.entry("/api/v1/bot-runtime", ApiPermission.BOT_READ),
      Map.entry("/api/v1/bot/runtime", ApiPermission.BOT_READ),
      Map.entry("/api/v1/audit", ApiPermission.AUDIT_READ),
      Map.entry("/api/v1/pilot", ApiPermission.ANALYTICS_READ),
      Map.entry("/api/v1/channels", ApiPermission.ADMIN_SETTINGS_READ),
      Map.entry("/api/v1/channel-identities", ApiPermission.ADMIN_SETTINGS_READ),
      Map.entry("/api/v1/ai-work", ApiPermission.REVIEW_READ),
      Map.entry("/api/v1/runtime", ApiPermission.RUNTIME_ENTITLEMENT_READ),
      // OP-CAP-17A: document trust is read-only this stage; reads require TRUST_READ.
      Map.entry("/api/v1/trust", ApiPermission.TRUST_READ)
  );

  public ApiPermissionInterceptor(ApiPermissionGuard guard) {
    this.guard = guard;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    ApiPermission permission = permissionFor(request.getMethod(), request.getRequestURI());
    if (permission != null) {
      guard.require(request, permission);
    }
    return true;
  }

  private ApiPermission permissionFor(String method, String path) {
    if (HttpMethod.OPTIONS.matches(method)) {
      return null;
    }
    if ((path.startsWith("/api/v1/bot-runtime") || path.startsWith("/api/v1/bot/runtime")) && !HttpMethod.GET.matches(method)) {
      return ApiPermission.BOT_ACTION;
    }
    if (path.startsWith("/api/v1/channel-identities") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.CHANNEL_IDENTITY_ACTION;
    }
    if (path.startsWith("/api/v1/channel-gateway/messages") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.INTAKE_WRITE;
    }
    if (path.startsWith("/api/v1/internal/ai-processing-results") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.AI_RESULT_INTAKE;
    }
    if (path.startsWith("/api/v1/ai-work") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.AI_WORK_ACTION;
    }
    // OP-CAP-16I: runtime governance commands (plan/feature mutations) require the dedicated manage
    // permission; reads fall through to RUNTIME_ENTITLEMENT_READ via the prefix map below.
    if (path.startsWith("/api/v1/runtime") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.RUNTIME_ENTITLEMENT_MANAGE;
    }
    if (path.startsWith("/api/v1/operator-review") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.REVIEW_ACTION;
    }
    if (path.startsWith("/api/v1/validation-review") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.REVIEW_ACTION;
    }
    // OP-CAP-11F: pilot shadow-mode reads require ANALYTICS_READ; recording shadow runs and
    // human corrections is a review-significant action and requires REVIEW_ACTION.
    if (path.startsWith("/api/v1/pilot") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.REVIEW_ACTION;
    }
    if ((path.startsWith("/api/v1/workspace/draft-quotes") || path.startsWith("/api/v1/workspace/draft-orders")) && !HttpMethod.GET.matches(method)) {
      return ApiPermission.REVIEW_ACTION;
    }
    if (path.startsWith("/api/v1/quote-review") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.REVIEW_ACTION;
    }
    if ((path.startsWith("/api/v1/quotes") || path.startsWith("/api/v1/quote-transactions")) && !HttpMethod.GET.matches(method)) {
      return ApiPermission.QUOTE_ACTION;
    }
    if (path.startsWith("/api/v1/extractions") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.EXTRACTION_RUN;
    }
    // OP-CAP-14C: operator validation-review commands (corrections, issue resolutions, approval
    // requests) are review-significant actions, not validation-engine triggers — they require
    // REVIEW_ACTION. Must be checked before the generic VALIDATION_RUN rule below.
    if (path.startsWith("/api/v1/validations/") && path.contains("/review") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.REVIEW_ACTION;
    }
    if (path.startsWith("/api/v1/validations") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.VALIDATION_RUN;
    }
    if (path.startsWith("/api/v1/intake") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.INTAKE_WRITE;
    }
    return readPrefixes.entrySet().stream()
        .filter(entry -> path.startsWith(entry.getKey()))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }
}
