package com.orderpilot.security;

import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

@Component
public class ApiRouteSecurityPolicy {
  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
  private static final String INTERNAL_SUPPORT_BASE = "/api/v1/internal/support";

  private static final List<PrefixRule> PREFIX_RULES = List.of(
      rule("/api/v1/analytics", ApiPermission.ANALYTICS_READ, ApiPermission.ANALYTICS_MANAGE),
      rule("/api/v1/commerce-intelligence", ApiPermission.ANALYTICS_READ, ApiPermission.ANALYTICS_MANAGE),
      rule("/api/v1/command-center", ApiPermission.ANALYTICS_READ, ApiPermission.ANALYTICS_MANAGE),
      rule("/api/v1/order-journeys", ApiPermission.ANALYTICS_READ, ApiPermission.REVIEW_ACTION),
      rule("/api/stage8/analytics", ApiPermission.ANALYTICS_READ, ApiPermission.ANALYTICS_MANAGE),
      rule("/api/stage8/reconciliation", ApiPermission.ANALYTICS_READ, ApiPermission.ANALYTICS_MANAGE),
      rule("/api/stage8/value", ApiPermission.ANALYTICS_READ, ApiPermission.ANALYTICS_MANAGE),
      rule("/api/v1/intake", ApiPermission.INTAKE_READ, ApiPermission.INTAKE_WRITE),
      rule("/api/v1/processing/jobs", ApiPermission.INTAKE_READ, ApiPermission.INTAKE_WRITE),
      rule("/api/v1/webhooks/events", ApiPermission.INTAKE_READ, ApiPermission.INTAKE_WRITE),
      rule("/api/v1/extractions", ApiPermission.EXTRACTION_READ, ApiPermission.EXTRACTION_RUN),
      rule("/api/v1/validations", ApiPermission.VALIDATION_READ, ApiPermission.VALIDATION_RUN),
      rule("/api/v1/operator-review", ApiPermission.REVIEW_READ, ApiPermission.REVIEW_ACTION),
      rule("/api/v1/ai-validation-handoffs", ApiPermission.REVIEW_READ, ApiPermission.REVIEW_ACTION),
      rule("/api/v1/validation-review", ApiPermission.REVIEW_READ, ApiPermission.REVIEW_ACTION),
      rule("/api/v1/workspace", ApiPermission.REVIEW_READ, ApiPermission.REVIEW_ACTION),
      rule("/api/v1/quote-review", ApiPermission.REVIEW_READ, ApiPermission.REVIEW_ACTION),
      rule("/api/v1/outbox-events", ApiPermission.CHANGE_REQUEST_READ, ApiPermission.CHANGE_REQUEST_EXECUTE),
      rule("/api/v1/quotes", ApiPermission.QUOTE_READ, ApiPermission.QUOTE_ACTION),
      rule("/api/v1/quote-transactions", ApiPermission.QUOTE_READ, ApiPermission.QUOTE_ACTION),
      rule("/api/v1/bot-runtime", ApiPermission.BOT_READ, ApiPermission.BOT_ACTION),
      rule("/api/v1/bot/runtime", ApiPermission.BOT_READ, ApiPermission.BOT_ACTION),
      rule("/api/v1/pilot", ApiPermission.ANALYTICS_READ, ApiPermission.REVIEW_ACTION),
      rule("/api/v1/integrations", ApiPermission.ADMIN_SETTINGS_READ, ApiPermission.ADMIN_SETTINGS_MANAGE),
      rule("/api/v1/channels", ApiPermission.ADMIN_SETTINGS_READ, ApiPermission.ADMIN_SETTINGS_MANAGE),
      rule("/api/v1/channel-identities", ApiPermission.ADMIN_SETTINGS_READ, ApiPermission.CHANNEL_IDENTITY_ACTION),
      rule("/api/v1/ai-work", ApiPermission.REVIEW_READ, ApiPermission.AI_WORK_ACTION),
      // OP-CAP-27D: read-only runtime-control telemetry for the RFQ/AI/demo path is an operator-facing
      // ANALYTICS_READ read. It MUST precede the "/api/v1/runtime" entitlement rule below: both are
      // prefixes of "/api/v1/runtime-control", and the first match wins, so the more specific
      // telemetry rule must be listed first (otherwise it would inherit RUNTIME_ENTITLEMENT_READ).
      rule("/api/v1/runtime-control", ApiPermission.ANALYTICS_READ, ApiPermission.ANALYTICS_MANAGE),
      rule("/api/v1/runtime", ApiPermission.RUNTIME_ENTITLEMENT_READ, ApiPermission.RUNTIME_ENTITLEMENT_MANAGE),
      rule("/api/v1/imports", ApiPermission.ADMIN_SETTINGS_READ, ApiPermission.ADMIN_SETTINGS_MANAGE),
      rule("/api/v1/import-jobs", ApiPermission.ADMIN_SETTINGS_READ, ApiPermission.ADMIN_SETTINGS_MANAGE),
      rule("/api/v1/customers", ApiPermission.ADMIN_SETTINGS_READ, ApiPermission.ADMIN_SETTINGS_MANAGE),
      rule("/api/v1/products", ApiPermission.ADMIN_SETTINGS_READ, ApiPermission.ADMIN_SETTINGS_MANAGE),
      rule("/api/v1/inventory", ApiPermission.ADMIN_SETTINGS_READ, ApiPermission.ADMIN_SETTINGS_MANAGE),
      rule("/api/v1/pricing", ApiPermission.ADMIN_SETTINGS_READ, ApiPermission.ADMIN_SETTINGS_MANAGE),
      rule("/api/v1/discounts", ApiPermission.ADMIN_SETTINGS_READ, ApiPermission.ADMIN_SETTINGS_MANAGE),
      rule("/api/v1/margins", ApiPermission.ADMIN_SETTINGS_READ, ApiPermission.ADMIN_SETTINGS_MANAGE),
      rule("/api/v1/reconciliation", ApiPermission.ANALYTICS_READ, ApiPermission.ANALYTICS_MANAGE),
      rule("/api/v1/demo", ApiPermission.ADMIN_SETTINGS_READ, ApiPermission.ADMIN_SETTINGS_MANAGE),
      rule("/api/v1/audit", ApiPermission.AUDIT_READ, ApiPermission.ADMIN_SETTINGS_MANAGE),
      rule("/api/v1/trust", ApiPermission.TRUST_READ, ApiPermission.TRUST_RISK_EVALUATE),
      rule("/api/stage9/connectors", ApiPermission.ADMIN_SETTINGS_READ, ApiPermission.ADMIN_SETTINGS_MANAGE),
      rule("/api/stage9/integrations", ApiPermission.ADMIN_SETTINGS_READ, ApiPermission.ADMIN_SETTINGS_MANAGE),
      rule("/api/stage9/connector-sync-runs", ApiPermission.ADMIN_SETTINGS_READ, ApiPermission.ADMIN_SETTINGS_MANAGE),
      rule("/api/stage9/connector-audit", ApiPermission.ADMIN_SETTINGS_READ, ApiPermission.ADMIN_SETTINGS_MANAGE)
  );

  public Optional<RouteDecision> classify(String method, String path) {
    if (method == null || path == null || !path.startsWith("/api/")) {
      return Optional.empty();
    }
    if (HttpMethod.OPTIONS.matches(method)) {
      return Optional.empty();
    }
    if (HttpMethod.GET.matches(method) && matchesAny(path, ApiSecurityWebConfig.PUBLIC_GET_ROUTES)) {
      return Optional.of(RouteDecision.publicRoute(SecurityClassification.PUBLIC_INTENTIONAL, "health endpoint"));
    }
    // OP-CAP-46C: the secure order-journey tracking link is public-with-token. There is no tenant/actor
    // header and no permission grant; the opaque, expiring token in the path is the sole credential and
    // the backend derives the tenant/journey scope from it. Read-only — never an external write.
    if (HttpMethod.GET.matches(method)
        && matchesAny(path, ApiSecurityWebConfig.PUBLIC_GET_SECURE_LINK_ROUTES)) {
      return Optional.of(RouteDecision.publicRoute(
          SecurityClassification.SECURE_TRACKING_LINK_PUBLIC_WITH_TOKEN,
          "secure tracking link resolved by opaque expiring token; tenant/journey scope derived from the token, never the request"));
    }
    if (HttpMethod.POST.matches(method) && matchesAny(path, ApiSecurityWebConfig.PUBLIC_POST_WEBHOOK_ROUTES)) {
      return Optional.of(RouteDecision.publicRoute(
          SecurityClassification.WEBHOOK_PUBLIC_WITH_SIGNATURE_OR_TOKEN,
          "provider-facing webhook allowlist"));
    }
    return protectedDecision(method, path);
  }

  public Optional<ApiPermission> requiredPermissionFor(String method, String path) {
    return classify(method, path).map(RouteDecision::requiredPermission);
  }

  private Optional<RouteDecision> protectedDecision(String method, String path) {
    if (path.startsWith("/api/stage8/reconciliation/refresh") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_REFRESH_RECOMPUTE, ApiPermission.ANALYTICS_MANAGE);
    }
    if (path.startsWith("/api/stage8/value/roi-assumptions") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_ADMIN_CONFIG, ApiPermission.ANALYTICS_MANAGE);
    }
    if (path.startsWith("/api/stage9/integrations") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_ADMIN_CONFIG, ApiPermission.ADMIN_SETTINGS_MANAGE);
    }
    if (path.startsWith("/api/v1/channel-gateway/messages") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_CREATE, ApiPermission.INTAKE_WRITE);
    }
    if (path.startsWith("/api/v1/internal/ai-validations") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_CREATE, ApiPermission.AI_RESULT_INTAKE);
    }
    if (path.startsWith("/api/v1/internal/ai-processing-results") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_CREATE, ApiPermission.AI_RESULT_INTAKE);
    }
    if (path.startsWith("/api/v1/internal/extractions") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_CREATE, ApiPermission.EXTRACTION_RUN);
    }
    if (path.startsWith("/api/v1/internal/processing-jobs") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_UPDATE, ApiPermission.AI_RESULT_INTAKE);
    }
    if (path.equals(INTERNAL_SUPPORT_BASE) || path.startsWith(INTERNAL_SUPPORT_BASE + "/")) {
      return supportDecision(method, path);
    }
    // OP-CAP-27D follow-up (PR #253): the read-only runtime-control telemetry surface must never inherit
    // the generic runtime-governance write rule below. "/api/v1/runtime-control" starts with
    // "/api/v1/runtime", so without this guard a POST/PUT/PATCH/DELETE to a runtime-control path would
    // classify as RUNTIME_ENTITLEMENT_MANAGE. There is no runtime-control write endpoint in this slice,
    // so any non-GET is fail-closed (Optional.empty -> global default-deny). GET falls through to the
    // "/api/v1/runtime-control" PREFIX_RULE (ANALYTICS_READ).
    if (path.equals("/api/v1/runtime-control") || path.startsWith("/api/v1/runtime-control/")) {
      if (!HttpMethod.GET.matches(method)) {
        return Optional.empty();
      }
      // GET: defer to PREFIX_RULES ("/api/v1/runtime-control" -> ANALYTICS_READ).
    } else if (path.startsWith("/api/v1/runtime") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_RUNTIME_MANAGE, ApiPermission.RUNTIME_ENTITLEMENT_MANAGE);
    }
    if (path.startsWith("/api/v1/change-requests") && !HttpMethod.GET.matches(method)) {
      return changeRequestDecision(path, false);
    }
    if (path.startsWith("/api/v1/change-requests")) {
      return protectedRoute(SecurityClassification.PROTECTED_READ, ApiPermission.CHANGE_REQUEST_READ);
    }
    if (path.startsWith("/api/stage9/change-requests") && !HttpMethod.GET.matches(method)) {
      return changeRequestDecision(path, true);
    }
    if (path.startsWith("/api/stage9/change-requests")) {
      return protectedRoute(SecurityClassification.PROTECTED_READ, ApiPermission.CHANGE_REQUEST_READ);
    }
    if (path.startsWith("/api/v1/validations/") && path.contains("/review") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_UPDATE, ApiPermission.REVIEW_ACTION);
    }
    if (path.startsWith("/api/v1/trust/ai-events") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_REFRESH_RECOMPUTE, ApiPermission.TRUST_AI_EVENT_PROCESS);
    }
    if (path.startsWith("/api/v1/trust/ai-events")) {
      return protectedRoute(SecurityClassification.PROTECTED_READ, ApiPermission.TRUST_AI_EVENT_READ);
    }
    if (path.startsWith("/api/v1/trust/operator-corrections") && path.endsWith("/approve-learning")
        && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_APPROVE, ApiPermission.TRUST_OPERATOR_CORRECTION_APPROVE);
    }
    if (path.startsWith("/api/v1/trust/operator-corrections") && path.endsWith("/reject-learning")
        && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_REJECT, ApiPermission.TRUST_OPERATOR_CORRECTION_REJECT);
    }
    if (path.startsWith("/api/v1/trust/operator-corrections") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(classificationForMethod(method), ApiPermission.TRUST_OPERATOR_CORRECTION_WRITE);
    }
    if (path.startsWith("/api/v1/trust/operator-corrections")) {
      return protectedRoute(SecurityClassification.PROTECTED_READ, ApiPermission.TRUST_OPERATOR_CORRECTION_READ);
    }
    if (path.startsWith("/api/v1/trust/ai-memory/advisory-retrieval")) {
      return protectedRoute(SecurityClassification.PROTECTED_READ, ApiPermission.TRUST_AI_MEMORY_READ);
    }
    if (path.startsWith("/api/v1/trust/ai-memory/evaluations") && path.endsWith("/execute")
        && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_EXECUTE, ApiPermission.TRUST_AI_MEMORY_EVALUATION_RUN);
    }
    if (path.startsWith("/api/v1/trust/ai-memory/evaluations/batch-runs") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_EXECUTE, ApiPermission.TRUST_AI_MEMORY_EVALUATION_RUN);
    }
    if (path.startsWith("/api/v1/trust/ai-memory/evaluations") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(classificationForMethod(method), ApiPermission.TRUST_AI_MEMORY_EVALUATION_WRITE);
    }
    if (path.startsWith("/api/v1/trust/ai-memory/evaluations")) {
      return protectedRoute(SecurityClassification.PROTECTED_READ, ApiPermission.TRUST_AI_MEMORY_EVALUATION_READ);
    }
    if (path.startsWith("/api/v1/trust/ai-memory") && path.endsWith("/invalidate") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_DELETE, ApiPermission.TRUST_AI_MEMORY_INVALIDATE);
    }
    if (path.startsWith("/api/v1/trust/ai-memory") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(classificationForMethod(method), ApiPermission.TRUST_AI_MEMORY_WRITE);
    }
    if (path.startsWith("/api/v1/trust/ai-memory")) {
      return protectedRoute(SecurityClassification.PROTECTED_READ, ApiPermission.TRUST_AI_MEMORY_READ);
    }
    if (path.startsWith("/api/v1/trust/ai-runtime") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_CREATE, ApiPermission.TRUST_AI_RUNTIME_TRACE_WRITE);
    }
    if (path.startsWith("/api/v1/trust/ai-runtime")) {
      return protectedRoute(SecurityClassification.PROTECTED_READ, ApiPermission.TRUST_AI_RUNTIME_TRACE_READ);
    }
    if (path.startsWith("/api/v1/trust/analytics/rebuild") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_REFRESH_RECOMPUTE, ApiPermission.TRUST_ANALYTICS_REBUILD);
    }
    if (path.startsWith("/api/v1/trust/analytics")) {
      return protectedRoute(SecurityClassification.PROTECTED_READ, ApiPermission.TRUST_ANALYTICS_READ);
    }
    if (path.startsWith("/api/v1/trust/risk-decisions") && path.endsWith("/override") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_APPROVE, ApiPermission.TRUST_RISK_OVERRIDE);
    }
    if (path.startsWith("/api/v1/trust/risk-decisions") && !HttpMethod.GET.matches(method)) {
      return protectedRoute(SecurityClassification.PROTECTED_CREATE, ApiPermission.TRUST_RISK_EVALUATE);
    }

    return PREFIX_RULES.stream()
        .filter(rule -> path.startsWith(rule.prefix()))
        .findFirst()
        .map(rule -> rule.decision(method));
  }

  // OP-CAP-51: the internal owner-company support/maintenance surface. Every route is protected by a
  // dedicated STAFF_* permission — none of these is a tenant business permission, so a tenant
  // operator/demo permission header can never satisfy them. Distinct verbs map to distinct permissions so
  // a read grant can never reach grant-management, maintenance recording, or a data-repair dry-run.
  // Unknown paths and wrong-method variants remain unclassified and hit the global /api/** default-deny.
  private Optional<RouteDecision> supportDecision(String method, String path) {
    boolean write = !HttpMethod.GET.matches(method);
    // OP-CAP-53: break-glass must be matched BEFORE the incident branch — a break-glass create path is
    // nested under an incident (.../incidents/{id}/break-glass-requests) and so contains both substrings.
    if (HttpMethod.POST.matches(method)
        && (matches(path, INTERNAL_SUPPORT_BASE + "/tenants/*/incidents/*/break-glass-requests")
            || matches(path, INTERNAL_SUPPORT_BASE + "/tenants/*/break-glass-requests/*/approve")
            || matches(path, INTERNAL_SUPPORT_BASE + "/tenants/*/break-glass-requests/*/reject")
            || matches(path, INTERNAL_SUPPORT_BASE + "/tenants/*/break-glass-requests/*/revoke"))) {
      if (write && path.endsWith("/approve")) {
        return protectedRoute(SecurityClassification.PROTECTED_APPROVE, ApiPermission.STAFF_BREAK_GLASS_APPROVE);
      }
      if (write && path.endsWith("/reject")) {
        return protectedRoute(SecurityClassification.PROTECTED_REJECT, ApiPermission.STAFF_BREAK_GLASS_APPROVE);
      }
      if (write && path.endsWith("/revoke")) {
        return protectedRoute(SecurityClassification.PROTECTED_UPDATE, ApiPermission.STAFF_BREAK_GLASS_REVOKE);
      }
      return protectedRoute(SecurityClassification.PROTECTED_CREATE, ApiPermission.STAFF_BREAK_GLASS_REQUEST);
    }
    // OP-CAP-53: incident records. Distinct verbs map to distinct STAFF_INCIDENT_* permissions; closing is
    // separated from creating so an incident-creator cannot silently close incidents from the create grant.
    if ((HttpMethod.GET.matches(method) && matches(path, INTERNAL_SUPPORT_BASE + "/incidents/*"))
        || (HttpMethod.POST.matches(method)
            && (path.equals(INTERNAL_SUPPORT_BASE + "/incidents")
                || matches(path, INTERNAL_SUPPORT_BASE + "/incidents/*/close")))) {
      if (write && path.endsWith("/close")) {
        return protectedRoute(SecurityClassification.PROTECTED_UPDATE, ApiPermission.STAFF_INCIDENT_CLOSE);
      }
      return write
          ? protectedRoute(SecurityClassification.PROTECTED_CREATE, ApiPermission.STAFF_INCIDENT_CREATE)
          : protectedRoute(SecurityClassification.PROTECTED_READ, ApiPermission.STAFF_INCIDENT_READ);
    }
    if ((HttpMethod.GET.matches(method)
            && matches(path, INTERNAL_SUPPORT_BASE + "/tenants/*/access-grants"))
        || (HttpMethod.POST.matches(method)
            && (path.equals(INTERNAL_SUPPORT_BASE + "/access-grants")
                || matches(path, INTERNAL_SUPPORT_BASE + "/access-grants/*/revoke")
                || matches(path, INTERNAL_SUPPORT_BASE + "/access-grants/*/approve")
                || matches(path, INTERNAL_SUPPORT_BASE + "/access-grants/*/reject")))) {
      // OP-CAP-52: approving/rejecting a grant is a separate, stronger authority than creating/revoking it,
      // so a STAFF_SUPPORT_GRANT_MANAGE actor that minted a grant request cannot also approve it.
      if (write && (path.endsWith("/approve") || path.endsWith("/reject"))) {
        return protectedRoute(approveOrReject(path), ApiPermission.STAFF_SUPPORT_GRANT_APPROVE);
      }
      return write
          ? protectedRoute(classificationForMethod(method), ApiPermission.STAFF_SUPPORT_GRANT_MANAGE)
          : protectedRoute(SecurityClassification.PROTECTED_READ, ApiPermission.STAFF_SUPPORT_READ);
    }
    if (HttpMethod.POST.matches(method)
        && matches(path, INTERNAL_SUPPORT_BASE + "/tenants/*/maintenance-records")) {
      return protectedRoute(SecurityClassification.PROTECTED_CREATE, ApiPermission.STAFF_MAINTENANCE_RECORD);
    }
    // OP-CAP-55: read-only internal support operations visibility. GET is the only implemented verb and
    // requires STAFF_SUPPORT_READ; write-shaped variants remain unclassified and hit default-deny.
    if (HttpMethod.GET.matches(method)
        && (matches(path, INTERNAL_SUPPORT_BASE + "/tenants/*/operations/summary")
            || matches(path, INTERNAL_SUPPORT_BASE + "/tenants/*/operations/timeline"))) {
      return protectedRoute(SecurityClassification.PROTECTED_READ, ApiPermission.STAFF_SUPPORT_READ);
    }
    if ((HttpMethod.GET.matches(method)
            && matches(path, INTERNAL_SUPPORT_BASE + "/tenants/*/data-repair-requests/*/operations-view"))
        || (HttpMethod.POST.matches(method)
            && (matches(path, INTERNAL_SUPPORT_BASE + "/tenants/*/data-repair-requests/dry-run")
                || matches(path, INTERNAL_SUPPORT_BASE + "/tenants/*/data-repair-requests/*/request-approval")
                || matches(path, INTERNAL_SUPPORT_BASE + "/tenants/*/data-repair-requests/*/approve")
                || matches(path, INTERNAL_SUPPORT_BASE + "/tenants/*/data-repair-requests/*/reject")
                || matches(path, INTERNAL_SUPPORT_BASE + "/tenants/*/data-repair-requests/*/execute")
                || matches(
                    path,
                    INTERNAL_SUPPORT_BASE
                        + "/tenants/*/data-repair-requests/*/execute-processing-job-repair")))) {
      if (path.endsWith("/operations-view")) {
        return protectedRoute(SecurityClassification.PROTECTED_READ, ApiPermission.STAFF_SUPPORT_READ);
      }
      // OP-CAP-54: the ONE bounded real-execution verb. Matched BEFORE the generic /execute stub so the
      // processing-job status-repair executor (the only path that can mutate a processing_job row) requires
      // its own dedicated, stronger STAFF_PROCESSING_JOB_REPAIR_EXECUTE permission. Every other data-repair
      // verb is unaffected.
      if (write && path.endsWith("/execute-processing-job-repair")) {
        return protectedRoute(SecurityClassification.PROTECTED_EXECUTE, ApiPermission.STAFF_PROCESSING_JOB_REPAIR_EXECUTE);
      }
      // OP-CAP-52: distinct data-repair verbs map to distinct permissions. The execution attempt is the
      // strongest (and only reaches a disabled stub); approve/reject is the approver tier; everything else
      // (dry-run, request-approval) stays at the requester tier STAFF_DATA_REPAIR_DRYRUN.
      if (write && path.endsWith("/execute")) {
        return protectedRoute(SecurityClassification.PROTECTED_EXECUTE, ApiPermission.STAFF_DATA_REPAIR_EXECUTION_ATTEMPT);
      }
      if (write && (path.endsWith("/approve") || path.endsWith("/reject"))) {
        return protectedRoute(approveOrReject(path), ApiPermission.STAFF_DATA_REPAIR_APPROVE);
      }
      return protectedRoute(SecurityClassification.PROTECTED_CREATE, ApiPermission.STAFF_DATA_REPAIR_DRYRUN);
    }
    if (HttpMethod.GET.matches(method)
        && matches(path, INTERNAL_SUPPORT_BASE + "/tenants/*/diagnostics")) {
      return protectedRoute(SecurityClassification.PROTECTED_READ, ApiPermission.STAFF_SUPPORT_READ);
    }
    // OP-CAP-57: the read-only internal tenant locator + per-tenant support context. Both are GET-only and
    // require STAFF_SUPPORT_READ; the locator service is the second-layer JIT grant boundary. Write-shaped
    // variants remain unclassified and hit default-deny.
    if (HttpMethod.GET.matches(method)
        && (path.equals(INTERNAL_SUPPORT_BASE + "/tenants/search")
            || matches(path, INTERNAL_SUPPORT_BASE + "/tenants/*/support-context"))) {
      return protectedRoute(SecurityClassification.PROTECTED_READ, ApiPermission.STAFF_SUPPORT_READ);
    }
    // Fail closed: only the explicit controller route/method matrix above is classified.
    return Optional.empty();
  }

  private Optional<RouteDecision> changeRequestDecision(String path, boolean stage9) {
    if (path.endsWith("/approve") || (!stage9 && path.endsWith("/approve-internal"))) {
      return protectedRoute(SecurityClassification.PROTECTED_APPROVE, ApiPermission.CHANGE_REQUEST_APPROVE);
    }
    if (path.endsWith("/reject") || path.endsWith("/cancel")) {
      return protectedRoute(SecurityClassification.PROTECTED_REJECT, ApiPermission.CHANGE_REQUEST_REJECT);
    }
    if (path.endsWith("/execute") || path.endsWith("/retry") || path.endsWith("/execution-disabled")) {
      return protectedRoute(SecurityClassification.PROTECTED_EXECUTE, ApiPermission.CHANGE_REQUEST_EXECUTE);
    }
    return protectedRoute(SecurityClassification.PROTECTED_CREATE, ApiPermission.CHANGE_REQUEST_CREATE);
  }

  private static Optional<RouteDecision> protectedRoute(
      SecurityClassification classification,
      ApiPermission permission) {
    return Optional.of(new RouteDecision(classification, permission, null));
  }

  private static SecurityClassification approveOrReject(String path) {
    return path.endsWith("/reject") ? SecurityClassification.PROTECTED_REJECT : SecurityClassification.PROTECTED_APPROVE;
  }

  private static SecurityClassification classificationForMethod(String method) {
    if (HttpMethod.POST.matches(method)) {
      return SecurityClassification.PROTECTED_CREATE;
    }
    if (HttpMethod.DELETE.matches(method)) {
      return SecurityClassification.PROTECTED_DELETE;
    }
    return SecurityClassification.PROTECTED_UPDATE;
  }

  private static boolean matchesAny(String path, String[] patterns) {
    for (String pattern : patterns) {
      if (PATH_MATCHER.match(pattern, path)) {
        return true;
      }
    }
    return false;
  }

  private static boolean matches(String path, String pattern) {
    return PATH_MATCHER.match(pattern, path);
  }

  private static PrefixRule rule(String prefix, ApiPermission readPermission, ApiPermission writePermission) {
    return new PrefixRule(prefix, readPermission, writePermission);
  }

  public enum SecurityClassification {
    PUBLIC_INTENTIONAL,
    WEBHOOK_PUBLIC_WITH_SIGNATURE_OR_TOKEN,
    SECURE_TRACKING_LINK_PUBLIC_WITH_TOKEN,
    PROTECTED_READ,
    PROTECTED_CREATE,
    PROTECTED_UPDATE,
    PROTECTED_DELETE,
    PROTECTED_REFRESH_RECOMPUTE,
    PROTECTED_ADMIN_CONFIG,
    PROTECTED_APPROVE,
    PROTECTED_REJECT,
    PROTECTED_EXECUTE,
    PROTECTED_RUNTIME_MANAGE
  }

  public record RouteDecision(
      SecurityClassification classification,
      ApiPermission requiredPermission,
      String publicReason) {
    private static RouteDecision publicRoute(SecurityClassification classification, String publicReason) {
      return new RouteDecision(classification, null, publicReason);
    }

    public boolean isPublic() {
      return requiredPermission == null;
    }
  }

  private record PrefixRule(
      String prefix,
      ApiPermission readPermission,
      ApiPermission writePermission) {
    private RouteDecision decision(String method) {
      if (HttpMethod.GET.matches(method)) {
        return new RouteDecision(SecurityClassification.PROTECTED_READ, readPermission, null);
      }
      return new RouteDecision(classificationForMethod(method), writePermission, null);
    }
  }
}
