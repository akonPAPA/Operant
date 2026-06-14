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
    // OP-CAP-18: trust/AI event projector runtime. Processing (any non-GET) requires the stronger
    // TRUST_AI_EVENT_PROCESS; reads require TRUST_AI_EVENT_READ. Checked before the generic
    // /api/v1/trust -> TRUST_READ prefix mapping below.
    if (path.startsWith("/api/v1/trust/ai-events") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.TRUST_AI_EVENT_PROCESS;
    }
    if (path.startsWith("/api/v1/trust/ai-events")) {
      return ApiPermission.TRUST_AI_EVENT_READ;
    }
    // OP-CAP-18: operator correction learning loop. Approve/reject are dedicated permissions; other
    // writes require WRITE; reads require READ.
    if (path.startsWith("/api/v1/trust/operator-corrections") && path.endsWith("/approve-learning")
        && !HttpMethod.GET.matches(method)) {
      return ApiPermission.TRUST_OPERATOR_CORRECTION_APPROVE;
    }
    if (path.startsWith("/api/v1/trust/operator-corrections") && path.endsWith("/reject-learning")
        && !HttpMethod.GET.matches(method)) {
      return ApiPermission.TRUST_OPERATOR_CORRECTION_REJECT;
    }
    if (path.startsWith("/api/v1/trust/operator-corrections") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.TRUST_OPERATOR_CORRECTION_WRITE;
    }
    if (path.startsWith("/api/v1/trust/operator-corrections")) {
      return ApiPermission.TRUST_OPERATOR_CORRECTION_READ;
    }
    // OP-CAP-17F: AI memory governance. Invalidate is its own permission; create/supersede require
    // WRITE; reads require READ. AI runtime traces have dedicated read/write permissions. All checked
    // before the generic /api/v1/trust -> TRUST_READ prefix mapping below (these also share that prefix).
    if (path.startsWith("/api/v1/trust/ai-memory") && path.endsWith("/invalidate") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.TRUST_AI_MEMORY_INVALIDATE;
    }
    if (path.startsWith("/api/v1/trust/ai-memory") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.TRUST_AI_MEMORY_WRITE;
    }
    if (path.startsWith("/api/v1/trust/ai-memory")) {
      return ApiPermission.TRUST_AI_MEMORY_READ;
    }
    if (path.startsWith("/api/v1/trust/ai-runtime") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.TRUST_AI_RUNTIME_TRACE_WRITE;
    }
    if (path.startsWith("/api/v1/trust/ai-runtime")) {
      return ApiPermission.TRUST_AI_RUNTIME_TRACE_READ;
    }
    // OP-CAP-17E: trust analytics read models. Reads require the dedicated TRUST_ANALYTICS_READ; the
    // bounded tenant rebuild requires the stronger TRUST_ANALYTICS_REBUILD. These must be checked before
    // the generic /api/v1/trust -> TRUST_READ prefix mapping below (analytics also starts with that
    // prefix).
    if (path.startsWith("/api/v1/trust/analytics/rebuild") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.TRUST_ANALYTICS_REBUILD;
    }
    if (path.startsWith("/api/v1/trust/analytics")) {
      return ApiPermission.TRUST_ANALYTICS_READ;
    }
    // OP-CAP-17D: trust risk-decision writes. Override is stronger than evaluate and is checked first.
    // GET reads under /api/v1/trust fall through to the TRUST_READ prefix map below.
    if (path.startsWith("/api/v1/trust/risk-decisions") && path.endsWith("/override") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.TRUST_RISK_OVERRIDE;
    }
    if (path.startsWith("/api/v1/trust/risk-decisions") && !HttpMethod.GET.matches(method)) {
      return ApiPermission.TRUST_RISK_EVALUATE;
    }
    return readPrefixes.entrySet().stream()
        .filter(entry -> path.startsWith(entry.getKey()))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }
}
