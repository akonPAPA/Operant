/**
 * Default-deny BFF → Core route registry.
 *
 * Every forwardable route is bound explicitly: exact HTTP method, exact or narrowly
 * parameterized path, access plane, required permission, read/mutation classification,
 * allowed request content type, maximum body size, and CSRF requirement. There is no
 * prefix forwarding — a new Core route never becomes reachable through the BFF until it
 * is registered here. Internal/support/staff/admin/ops/diagnostics/maintenance/incident/
 * release/break-glass/webhook/public/demo planes are explicitly denied.
 *
 * Required permissions mirror apps/core-api ApiRouteSecurityPolicy; the BFF enforces them
 * against the server-side session as defense in depth (Core remains authoritative).
 */

import type { BffIdempotencyPolicy } from "./bff-idempotency-key.ts";
import type { StrictBodyPolicy } from "./bff-strict-body-policy.ts";

export type BffHttpMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

export type BffRouteRule = {
  method: BffHttpMethod;
  /** Slash-joined template; ":name" matches exactly one typed, validated path segment. */
  pattern: string;
  plane: "tenant-operator";
  permission: string;
  kind: "read" | "mutation";
  params: Record<string, BffPathParamType>;
  query: BffQueryPolicy;
  /** Required request content type for mutations with a body; null = no request body. */
  contentType: string | null;
  maxBodyBytes: number;
  csrfRequired: boolean;
  /**
   * Idempotency-Key policy (F01). Reads forbid the header; mutations accept it as optional.
   * A route whose Core handler *requires* a key can be tightened to "required" here without
   * any proxy change — validation and fail-closed behaviour are policy-driven.
   */
  idempotency: BffIdempotencyPolicy;
  allowIfMatch: boolean;
  /**
   * Optional strict request-body allowlist. When set, a present JSON body must be a plain object
   * whose keys are exactly within this allowlist (see bff-strict-body-policy). Unknown / authority
   * / server-state / prototype-pollution keys are rejected with 400 and zero Core calls. Routes
   * without a policy keep the canonicalize-and-forward behaviour (Core remains authoritative and
   * ignores unknown fields). This is applied only to high-authority mutation routes, not globally.
   */
  bodyPolicy?: StrictBodyPolicy;
};

const JSON_CONTENT_TYPE = "application/json";
const DEFAULT_MUTATION_BODY_LIMIT = 256 * 1024;

export type BffPathParamType = "uuid" | "positive-int" | "handle";
export type BffQueryValueType =
  | "uuid"
  | "positive-int"
  | "non-negative-int"
  | "bounded-int"
  | "enum"
  | "text";
export type BffQueryFieldPolicy = {
  type: BffQueryValueType;
  maxValues?: number;
  maxLength?: number;
  enumValues?: readonly string[];
  /** For "bounded-int": inclusive lower bound (defaults to 0). */
  min?: number;
  /** For "bounded-int": inclusive upper bound (required). */
  max?: number;
};
export type BffQueryPolicy = Record<string, BffQueryFieldPolicy>;

/**
 * Segment names that must never travel through the tenant operator BFF, regardless of
 * position. These planes (owner support & maintenance, staff, ops, webhooks, public,
 * demo, …) are outside tenant browser sessions by design.
 */
const DENIED_SEGMENTS = new Set([
  "internal",
  "support",
  "staff",
  "admin",
  "ops",
  "diagnostics",
  "maintenance",
  "incident",
  "incidents",
  "release",
  "releases",
  "break-glass",
  "break-glass-requests",
  "repair",
  "data-repair-requests",
  "webhook",
  "webhooks",
  "public",
  "demo",
  "platform",
  "connector-gateway"
]);

const UUID_SEGMENT = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const POSITIVE_INT_SEGMENT = /^[1-9][0-9]{0,8}$/;
const HANDLE_SEGMENT = /^[A-Za-z0-9][A-Za-z0-9._~@-]{0,127}$/;

const NO_QUERY: BffQueryPolicy = Object.freeze({});
const LIMIT_QUERY: BffQueryPolicy = Object.freeze({
  limit: { type: "positive-int", maxValues: 1 }
});
const QUOTE_CONVERSION_ATTEMPTS_QUERY: BffQueryPolicy = Object.freeze({
  limit: { type: "positive-int", maxValues: 1 },
  status: { type: "text", maxValues: 1, maxLength: 32 },
  sourceType: { type: "text", maxValues: 1, maxLength: 48 },
  customerRef: { type: "text", maxValues: 1, maxLength: 128 }
});
const ORDER_JOURNEY_BY_SOURCE_QUERY: BffQueryPolicy = Object.freeze({
  sourceType: {
    type: "enum",
    enumValues: ["QUOTE", "ORDER", "RFQ", "VALIDATION_RUN", "CHANNEL_MESSAGE"],
    maxValues: 1
  },
  sourceId: { type: "uuid", maxValues: 1 }
});
const DRAFT_REVIEW_QUEUE_QUERY: BffQueryPolicy = Object.freeze({
  status: { type: "text", maxValues: 1, maxLength: 32 },
  sourceReviewCaseId: { type: "uuid", maxValues: 1 },
  customerRef: { type: "text", maxValues: 1, maxLength: 128 },
  limit: { type: "positive-int", maxValues: 1 }
});
const PRODUCT_SEARCH_QUERY: BffQueryPolicy = Object.freeze({
  q: { type: "text", maxValues: 1, maxLength: 128 },
  limit: { type: "positive-int", maxValues: 1 }
});
const RFQ_HANDOFF_LIST_QUERY: BffQueryPolicy = Object.freeze({
  status: {
    type: "enum",
    enumValues: ["PENDING_REVIEW", "IN_REVIEW", "CONVERTED", "DISMISSED"],
    maxValues: 1
  },
  // Spring Pageable: page is zero-based (page=0 is the first page and MUST be valid);
  // size must be strictly positive (size=0 is invalid).
  page: { type: "non-negative-int", maxValues: 1 },
  size: { type: "positive-int", maxValues: 1 }
});
const AI_WORK_SUGGESTIONS_QUERY: BffQueryPolicy = Object.freeze({
  limit: { type: "positive-int", maxValues: 1 },
  sourceType: { type: "text", maxValues: 1, maxLength: 48 },
  sourceId: { type: "uuid", maxValues: 1 }
});
const REVIEW_DRAFT_QUEUE_QUERY: BffQueryPolicy = Object.freeze({
  draftType: { type: "text", maxValues: 1, maxLength: 32 },
  status: { type: "text", maxValues: 1, maxLength: 32 },
  limit: { type: "positive-int", maxValues: 1 },
  // offset is zero-based (offset=0 MUST be valid); non-numeric/overflow fail at the BFF.
  offset: { type: "non-negative-int", maxValues: 1 }
});
const DRAFT_PREVIEW_QUERY: BffQueryPolicy = Object.freeze({
  targetType: {
    type: "enum",
    enumValues: ["QUOTE", "ORDER"],
    maxValues: 1
  }
});

function paramsFor(pattern: string): Record<string, BffPathParamType> {
  const params: Record<string, BffPathParamType> = {};
  for (const segment of pattern.split("/")) {
    if (!segment.startsWith(":")) {
      continue;
    }
    const name = segment.slice(1);
    params[name] = name.endsWith("Id") ? "uuid" : "handle";
  }
  return params;
}

function read(
  pattern: string,
  permission: string,
  options?: { params?: Record<string, BffPathParamType>; query?: BffQueryPolicy }
): BffRouteRule {
  return {
    method: "GET",
    pattern,
    plane: "tenant-operator",
    permission,
    kind: "read",
    params: options?.params ?? paramsFor(pattern),
    query: options?.query ?? NO_QUERY,
    contentType: null,
    maxBodyBytes: 0,
    csrfRequired: false,
    idempotency: "forbidden",
    allowIfMatch: false
  };
}

function mutate(
  method: Exclude<BffHttpMethod, "GET">,
  pattern: string,
  permission: string,
  options?: {
    maxBodyBytes?: number;
    params?: Record<string, BffPathParamType>;
    query?: BffQueryPolicy;
    bodyPolicy?: StrictBodyPolicy;
    idempotency?: BffIdempotencyPolicy;
  }
): BffRouteRule {
  return {
    method,
    pattern,
    plane: "tenant-operator",
    permission,
    kind: "mutation",
    params: options?.params ?? paramsFor(pattern),
    query: options?.query ?? NO_QUERY,
    contentType: JSON_CONTENT_TYPE,
    maxBodyBytes: options?.maxBodyBytes ?? DEFAULT_MUTATION_BODY_LIMIT,
    csrfRequired: true,
    idempotency: options?.idempotency ?? "optional",
    allowIfMatch: false,
    bodyPolicy: options?.bodyPolicy
  };
}

// Strict request-body allowlists for the tenant quote-authority mutations. Business intent only:
// tenant comes from X-Tenant-Id (session-derived), actor from the trusted resolver, and the
// idempotency key travels in the Idempotency-Key header — none of them are accepted in the body.
const QUOTE_FROM_RFQ_BODY: StrictBodyPolicy = Object.freeze({
  allowedKeys: ["customerExternalRef", "requestedLocation", "requestedDiscountPercent", "requestedItems"],
  arrayItemKeys: { requestedItems: ["rawSkuOrAlias", "description", "quantity", "uom"] }
});
const QUOTE_APPROVAL_DECISION_BODY: StrictBodyPolicy = Object.freeze({
  allowedKeys: ["approvalRequestId", "reason", "comment"]
});

const ROUTE_RULES: BffRouteRule[] = [
  // Analytics / command center (ANALYTICS_READ)
  read("api/v1/analytics/overview", "ANALYTICS_READ"),
  read("api/v1/analytics/intake", "ANALYTICS_READ"),
  read("api/v1/analytics/validation", "ANALYTICS_READ"),
  read("api/v1/analytics/commerce/summary", "ANALYTICS_READ"),
  read("api/v1/command-center/summary", "ANALYTICS_READ"),
  read("api/v1/commerce-intelligence/demo-flow", "ANALYTICS_READ"),
  read("api/v1/runtime-control/demo-flow", "ANALYTICS_READ"),
  read("api/v1/reconciliation/cases", "ANALYTICS_READ"),
  mutate("POST", "api/v1/reconciliation/inventory/run", "ANALYTICS_MANAGE"),

  // Order journeys (read-only operator surface)
  read("api/v1/order-journeys", "ANALYTICS_READ", { query: LIMIT_QUERY }),
  read("api/v1/order-journeys/attention", "ANALYTICS_READ", { query: LIMIT_QUERY }),
  read("api/v1/order-journeys/projection-health", "ANALYTICS_READ"),
  read("api/v1/order-journeys/by-source", "ANALYTICS_READ", { query: ORDER_JOURNEY_BY_SOURCE_QUERY }),
  read("api/v1/order-journeys/:journeyId", "ANALYTICS_READ"),
  read("api/v1/order-journeys/:journeyId/operator-timeline", "ANALYTICS_READ"),
  mutate("POST", "api/v1/order-journeys/:journeyId/tracking-links", "REVIEW_ACTION"),
  read("api/v1/order-journeys/:journeyId/tracking-links", "ANALYTICS_READ"),
  mutate("POST", "api/v1/order-journeys/:journeyId/tracking-links/:linkId/revoke", "REVIEW_ACTION"),

  // Pilot evidence (read-only)
  read("api/v1/pilot/demo-scenarios", "ANALYTICS_READ"),
  read("api/v1/pilot/evidence-report", "ANALYTICS_READ"),
  read("api/v1/pilot/metrics", "ANALYTICS_READ"),
  read("api/v1/pilot/metrics/exceptions", "ANALYTICS_READ"),

  // Intake (read-only through the browser; uploads are not registered)
  read("api/v1/intake/documents", "INTAKE_READ"),
  read("api/v1/intake/documents/:documentId", "INTAKE_READ"),
  read("api/v1/intake/events", "INTAKE_READ"),
  read("api/v1/intake/jobs", "INTAKE_READ"),
  read("api/v1/intake/messages", "INTAKE_READ"),
  read("api/v1/intake/messages/:messageId", "INTAKE_READ"),

  // Extraction → validation bridge (Core exposes POST create only)
  mutate("POST", "api/v1/extractions/:extractionId/validation/review-case", "EXTRACTION_RUN"),

  // Validation runs and review drafts
  read("api/v1/validations/:validationRunId/review", "VALIDATION_READ"),
  read("api/v1/validations/:validationRunId/review/draft-status", "VALIDATION_READ"),
  read("api/v1/validations/:validationRunId/review/draftability", "VALIDATION_READ"),
  read("api/v1/validations/extractions/:extractionResultId/review", "VALIDATION_READ"),
  read("api/v1/validations/review-drafts", "VALIDATION_READ", { query: REVIEW_DRAFT_QUEUE_QUERY }),
  read("api/v1/validations/review-drafts/remediation-rollup", "VALIDATION_READ", {
    query: LIMIT_QUERY
  }),
  read(
    "api/v1/validations/review-drafts/:draftKind/:draftId/remediation-lineage",
    "VALIDATION_READ"
  ),
  read("api/v1/validations/runs/:validationRunId/product-matches", "VALIDATION_READ"),
  read("api/v1/validations/runs/:validationRunId/uom-normalizations", "VALIDATION_READ"),
  read("api/v1/validations/runs/:validationRunId/inventory-checks", "VALIDATION_READ"),
  read("api/v1/validations/runs/:validationRunId/price-checks", "VALIDATION_READ"),
  read("api/v1/validations/runs/:validationRunId/discount-checks", "VALIDATION_READ"),
  read("api/v1/validations/runs/:validationRunId/margin-checks", "VALIDATION_READ"),
  mutate("POST", "api/v1/validations/:validationRunId/review/corrections", "REVIEW_ACTION"),
  mutate("POST", "api/v1/validations/:validationRunId/review/approval-requests", "REVIEW_ACTION"),
  mutate("POST", "api/v1/validations/:validationRunId/review/draft-order", "REVIEW_ACTION"),
  mutate("POST", "api/v1/validations/:validationRunId/review/draft-quote", "REVIEW_ACTION"),
  mutate(
    "POST",
    "api/v1/validations/:validationRunId/review/issues/:issueId/resolution",
    "REVIEW_ACTION"
  ),

  // Validation review workspace
  read("api/v1/validation-review", "REVIEW_READ"),
  read("api/v1/validation-review/:reviewCaseId", "REVIEW_READ"),
  read("api/v1/validation-review/:reviewCaseId/draft-preview", "REVIEW_READ", {
    query: DRAFT_PREVIEW_QUERY
  }),
  mutate("POST", "api/v1/validation-review/:reviewCaseId/approve", "REVIEW_ACTION"),
  mutate("POST", "api/v1/validation-review/:reviewCaseId/reject", "REVIEW_ACTION"),
  mutate("POST", "api/v1/validation-review/:reviewCaseId/corrections/product", "REVIEW_ACTION"),
  mutate("POST", "api/v1/validation-review/:reviewCaseId/corrections/quantity", "REVIEW_ACTION"),
  mutate("POST", "api/v1/validation-review/:reviewCaseId/corrections/uom", "REVIEW_ACTION"),
  mutate("POST", "api/v1/validation-review/:reviewCaseId/issues/acknowledge", "REVIEW_ACTION"),
  mutate("POST", "api/v1/validation-review/:reviewCaseId/issues/override", "REVIEW_ACTION"),
  mutate("POST", "api/v1/validation-review/:reviewCaseId/substitutes/select", "REVIEW_ACTION"),
  mutate("POST", "api/v1/validation-review/:reviewCaseId/substitutes/reject", "REVIEW_ACTION"),
  mutate(
    "POST",
    "api/v1/validation-review/:reviewCaseId/approvals/:approvalRequestId/approve",
    "REVIEW_ACTION"
  ),
  mutate(
    "POST",
    "api/v1/validation-review/:reviewCaseId/approvals/:approvalRequestId/reject",
    "REVIEW_ACTION"
  ),
  mutate("POST", "api/v1/validation-review/:reviewCaseId/prepare-draft-quote", "REVIEW_ACTION"),
  mutate("POST", "api/v1/validation-review/:reviewCaseId/prepare-draft-order", "REVIEW_ACTION"),

  // Draft review workspace
  read("api/v1/workspace/draft-quotes/review-queue", "REVIEW_READ", {
    query: DRAFT_REVIEW_QUEUE_QUERY
  }),
  read("api/v1/workspace/draft-orders/review-queue", "REVIEW_READ", {
    query: DRAFT_REVIEW_QUEUE_QUERY
  }),
  read("api/v1/workspace/draft-quotes/:draftQuoteId/review", "REVIEW_READ"),
  read("api/v1/workspace/draft-orders/:draftOrderId/review", "REVIEW_READ"),
  read("api/v1/workspace/products/search", "REVIEW_READ", { query: PRODUCT_SEARCH_QUERY }),
  mutate("PATCH", "api/v1/workspace/draft-quotes/:draftQuoteId/lines/:lineId", "REVIEW_ACTION"),
  mutate("PATCH", "api/v1/workspace/draft-orders/:draftOrderId/lines/:lineId", "REVIEW_ACTION"),
  mutate("POST", "api/v1/workspace/draft-quotes/:draftQuoteId/mark-ready", "REVIEW_ACTION"),
  mutate("POST", "api/v1/workspace/draft-orders/:draftOrderId/mark-ready", "REVIEW_ACTION"),

  // Quote review cockpit
  read("api/v1/quote-review/queue", "REVIEW_READ"),
  read("api/v1/quote-review/conversion-attempts", "REVIEW_READ", {
    query: QUOTE_CONVERSION_ATTEMPTS_QUERY
  }),
  read("api/v1/quote-review/conversion-attempts/:attemptId", "REVIEW_READ"),
  read("api/v1/quote-review/:quoteId", "REVIEW_READ"),
  mutate("POST", "api/v1/quote-review/:quoteId/assemble-draft", "REVIEW_ACTION"),
  mutate("POST", "api/v1/quote-review/:quoteId/issues/:issueId/escalate", "REVIEW_ACTION"),
  mutate("POST", "api/v1/quote-review/:quoteId/issues/:issueId/resolve", "REVIEW_ACTION"),
  mutate("POST", "api/v1/quote-review/:quoteId/lines/:lineId/correct", "REVIEW_ACTION"),
  mutate(
    "POST",
    "api/v1/quote-review/:quoteId/lines/:lineId/substitutes/select",
    "REVIEW_ACTION"
  ),
  mutate(
    "POST",
    "api/v1/quote-review/:quoteId/lines/:lineId/substitutes/reject",
    "REVIEW_ACTION"
  ),

  // Quotes
  read("api/v1/quotes/:quoteId/approval-state", "QUOTE_READ"),
  read("api/v1/quotes/:quoteId/source-context", "QUOTE_READ"),
  // Tenant quote-authority mutations: the strict business contract requires a canonical opaque
  // Idempotency-Key (replay/dedup protection for high-authority actions). "required" here is the
  // single source of that policy — the proxy validates it after body processing, before signing.
  mutate("POST", "api/v1/quotes/from-rfq", "QUOTE_ACTION", {
    bodyPolicy: QUOTE_FROM_RFQ_BODY,
    idempotency: "required"
  }),
  mutate("POST", "api/v1/quotes/:quoteId/approve", "QUOTE_ACTION", {
    bodyPolicy: QUOTE_APPROVAL_DECISION_BODY,
    idempotency: "required"
  }),
  mutate("POST", "api/v1/quotes/:quoteId/reject", "QUOTE_ACTION", {
    bodyPolicy: QUOTE_APPROVAL_DECISION_BODY,
    idempotency: "required"
  }),
  mutate("POST", "api/v1/quotes/:quoteId/request-changes", "QUOTE_ACTION", {
    bodyPolicy: QUOTE_APPROVAL_DECISION_BODY,
    idempotency: "required"
  }),
  mutate("POST", "api/v1/quotes/:quoteId/convert-to-internal-order", "QUOTE_ACTION", {
    bodyPolicy: QUOTE_APPROVAL_DECISION_BODY,
    idempotency: "required"
  }),
  mutate("POST", "api/v1/quotes/drafts/from-rfq-handoff/:handoffId", "QUOTE_ACTION"),
  mutate("POST", "api/v1/quotes/drafts/from-rfq-handoff/:handoffId/decision", "QUOTE_ACTION"),

  // Quote transactions (channel/document conversion)
  mutate("POST", "api/v1/quote-transactions/from-channel-message/:messageId", "QUOTE_ACTION"),
  mutate(
    "POST",
    "api/v1/quote-transactions/from-inbound-document/:documentId",
    "QUOTE_ACTION"
  ),

  // Channels / RFQ handoffs
  read("api/v1/channels/bot-bridge/status", "ADMIN_SETTINGS_READ", { query: LIMIT_QUERY }),
  read("api/v1/channels/bot-events", "ADMIN_SETTINGS_READ", { query: LIMIT_QUERY }),
  read("api/v1/channels/rfq-handoffs", "ADMIN_SETTINGS_READ", { query: RFQ_HANDOFF_LIST_QUERY }),
  read("api/v1/channels/rfq-handoffs/:handoffId", "ADMIN_SETTINGS_READ"),
  mutate("POST", "api/v1/channels/rfq-handoffs/:handoffId/dismiss", "ADMIN_SETTINGS_MANAGE"),
  mutate(
    "POST",
    "api/v1/channels/rfq-handoffs/:handoffId/mark-converted",
    "ADMIN_SETTINGS_MANAGE"
  ),
  mutate("POST", "api/v1/channels/rfq-handoffs/:handoffId/start-review", "ADMIN_SETTINGS_MANAGE"),

  // Channel identities
  read("api/v1/channel-identities", "ADMIN_SETTINGS_READ"),
  read("api/v1/channel-identities/:identityId", "ADMIN_SETTINGS_READ"),
  mutate("POST", "api/v1/channel-identities/:identityId/link", "CHANNEL_IDENTITY_ACTION"),
  mutate("POST", "api/v1/channel-identities/:identityId/unlink", "CHANNEL_IDENTITY_ACTION"),
  mutate("POST", "api/v1/channel-identities/:identityId/block", "CHANNEL_IDENTITY_ACTION"),
  mutate(
    "POST",
    "api/v1/channel-identities/:identityId/needs-review",
    "CHANNEL_IDENTITY_ACTION"
  ),

  // Customers / master data (read-only)
  read("api/v1/customers", "ADMIN_SETTINGS_READ"),
  read("api/v1/customers/:customerId/contacts", "ADMIN_SETTINGS_READ"),
  read("api/v1/products", "ADMIN_SETTINGS_READ"),
  read("api/v1/inventory", "ADMIN_SETTINGS_READ"),

  // AI advisory work
  read("api/v1/ai-work/suggestions", "REVIEW_READ", { query: AI_WORK_SUGGESTIONS_QUERY }),
  read("api/v1/ai-work/suggestions/:suggestionId", "REVIEW_READ"),
  mutate("POST", "api/v1/ai-work/rfq-handoffs/:handoffId/suggestions", "AI_WORK_ACTION"),
  mutate("POST", "api/v1/ai-work/suggestions/:suggestionId/accept", "AI_WORK_ACTION"),
  mutate("POST", "api/v1/ai-work/suggestions/:suggestionId/reject", "AI_WORK_ACTION"),

  // Bot runtime operator surface
  read("api/v1/bot-runtime/configurations", "BOT_READ"),
  read("api/v1/bot-runtime/configurations/:connectionId", "BOT_READ"),
  read("api/v1/bot-runtime/conversations", "BOT_READ"),
  read("api/v1/bot-runtime/conversations/:conversationId", "BOT_READ"),
  read("api/v1/bot-runtime/handoffs", "BOT_READ"),
  read("api/v1/bot-runtime/settings", "BOT_READ"),
  mutate("PUT", "api/v1/bot-runtime/configurations/:connectionId", "BOT_ACTION"),
  mutate(
    "POST",
    "api/v1/bot-runtime/configurations/:connectionId/reset-defaults",
    "BOT_ACTION"
  ),
  mutate(
    "POST",
    "api/v1/bot-runtime/conversations/:conversationId/responses/draft",
    "BOT_ACTION"
  ),
  mutate(
    "POST",
    "api/v1/bot-runtime/conversations/:conversationId/review-handoff",
    "BOT_ACTION"
  ),
  mutate("POST", "api/v1/bot-runtime/messages/simulate", "BOT_ACTION"),
  mutate("POST", "api/v1/bot-runtime/responses/:responseId/mark-ready", "BOT_ACTION"),
  mutate("POST", "api/v1/bot-runtime/responses/:responseId/stub-send", "BOT_ACTION"),
  mutate("POST", "api/v1/bot-runtime/settings", "BOT_ACTION"),

  // Stage 8 analytics / reconciliation / value (tenant analytics plane)
  read("api/stage8/analytics/command-center", "ANALYTICS_READ"),
  read("api/stage8/reconciliation/cases", "ANALYTICS_READ"),
  read("api/stage8/reconciliation/summary", "ANALYTICS_READ"),
  read("api/stage8/reconciliation/products/:productId/timeline", "ANALYTICS_READ"),
  read("api/stage8/value/summary", "ANALYTICS_READ"),
  read("api/stage8/value/roi-assumptions", "ANALYTICS_READ"),
  read("api/stage8/value/export", "ANALYTICS_READ"),
  mutate("PUT", "api/stage8/value/roi-assumptions", "ANALYTICS_MANAGE"),

  // Stage 9 integration visibility (reads) + change-request creation
  read("api/stage9/change-requests", "CHANGE_REQUEST_READ"),
  read("api/stage9/connector-audit", "ADMIN_SETTINGS_READ"),
  read("api/stage9/connector-sync-runs", "ADMIN_SETTINGS_READ"),
  read("api/stage9/connectors/policies", "ADMIN_SETTINGS_READ"),
  read("api/stage9/integrations", "ADMIN_SETTINGS_READ"),
  mutate("POST", "api/stage9/change-requests", "CHANGE_REQUEST_CREATE")
];

function segmentsDenied(segments: string[]): boolean {
  return segments.some((segment) => DENIED_SEGMENTS.has(segment.toLowerCase()));
}

function paramMatches(type: BffPathParamType, actual: string): boolean {
  if (type === "uuid") {
    return UUID_SEGMENT.test(actual);
  }
  if (type === "positive-int") {
    return POSITIVE_INT_SEGMENT.test(actual);
  }
  return HANDLE_SEGMENT.test(actual);
}

function patternMatches(rule: BffRouteRule, segments: string[]): boolean {
  const patternSegments = rule.pattern.split("/");
  if (patternSegments.length !== segments.length) {
    return false;
  }
  for (let i = 0; i < patternSegments.length; i += 1) {
    const expected = patternSegments[i];
    const actual = segments[i];
    if (expected.startsWith(":")) {
      const paramType = rule.params[expected.slice(1)];
      if (!paramType || !paramMatches(paramType, actual)) {
        return false;
      }
      continue;
    }
    if (expected !== actual) {
      return false;
    }
  }
  return true;
}

/** Default deny: returns the single registered rule for (method, path) or null. */
export function matchBffRoute(segments: string[], method: string): BffRouteRule | null {
  if (segments.length < 3) {
    return null;
  }
  if (segmentsDenied(segments)) {
    return null;
  }
  const normalizedMethod = method.toUpperCase();
  for (const rule of ROUTE_RULES) {
    if (rule.method !== normalizedMethod) {
      continue;
    }
    if (patternMatches(rule, segments)) {
      return rule;
    }
  }
  return null;
}

export function isBffProxyPathAllowed(segments: string[], method: string): boolean {
  return matchBffRoute(segments, method) !== null;
}

export function corePathFromBffSegments(segments: string[]): string {
  return `/${segments.join("/")}`;
}

/** Exposed for registry contract tests. */
export function registeredBffRoutes(): readonly BffRouteRule[] {
  return ROUTE_RULES;
}
