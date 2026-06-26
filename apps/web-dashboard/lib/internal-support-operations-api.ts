// OP-CAP-56 — Internal Support Operations Visibility API client.
//
// Typed, READ-ONLY client over the OP-CAP-55 owner-company support operations endpoints:
//   GET /api/v1/internal/support/tenants/{tenantId}/operations/summary
//   GET /api/v1/internal/support/tenants/{tenantId}/operations/timeline
//   GET /api/v1/internal/support/tenants/{tenantId}/data-repair-requests/{requestId}/operations-view
//
// Data-boundary law (mirrors the backend OP-CAP-55 contract):
//  - This client only ever performs GET. It builds NO request body, so it cannot smuggle
//    tenant/actor/staff/approval/execution/audit authority fields into a payload.
//  - The target tenant is the trusted, server-resolved demo scope (NEXT_PUBLIC_DEMO_TENANT_ID). It flows
//    through the `X-Tenant-Id` header AND the path segment (the backend requires path == header tenant and
//    fails closed otherwise). It is never an editable, operator-typed field.
//  - The only client-supplied parameters are safe route/path locators (the data-repair requestId) and safe
//    bounded pagination params (page/size) already required by the backend contract.
//  - Errors are mapped to safe operator messages; the raw backend body is drained and never surfaced.

export type ApiResult<T> = {
  data?: T;
  error?: string;
};

// Mirrors SupportOperationsDtos.SupportOperationsSummaryResponse (backend-owned counts + lifecycle markers).
export type SupportOperationsSummary = {
  tenantId: string;
  openIncidents: number;
  criticalOpenIncidents: number;
  pendingBreakGlassRequests: number;
  approvedActiveBreakGlassRequests: number;
  pendingSupportGrants: number;
  activeSupportGrants: number;
  pendingDataRepairApprovals: number;
  approvedDataRepairRequests: number;
  executedProcessingJobRepairs: number;
  rejectedDataRepairRequests: number;
  latestActivityAt?: string | null;
  generatedAt: string;
  externalExecution: string;
};

// Mirrors SupportOperationsDtos.SupportOperationsTimelineEntry. Bounded lifecycle marker only — no reason
// text, no payload, no actor id.
export type SupportOperationsTimelineEntry = {
  category: string;
  eventType: string;
  referenceId: string;
  status: string;
  occurredAt: string;
};

// Mirrors SupportOperationsDtos.SupportOperationsTimelineResponse (always a bounded page).
export type SupportOperationsTimeline = {
  tenantId: string;
  page: number;
  pageSize: number;
  returnedCount: number;
  hasMore: boolean;
  entries: SupportOperationsTimelineEntry[];
  generatedAt: string;
};

// Mirrors SupportOperationsDtos.DataRepairOperationsViewResponse (one request's safe diagnostics).
export type DataRepairOperationsView = {
  requestId: string;
  tenantId: string;
  targetType: string;
  approvalStatus: string;
  executionStatus: string;
  dryRunSummary?: string | null;
  affectedTargetSummary?: string | null;
  processingJobId?: string | null;
  previousStatus?: string | null;
  newStatus?: string | null;
  executedAt?: string | null;
  executed: boolean;
  timeline: SupportOperationsTimelineEntry[];
  generatedAt: string;
  externalExecution: string;
};

export type TimelinePageParams = {
  page?: number;
  size?: number;
};

const DEFAULT_BASE_URL = "http://localhost:8080";

export const internalSupportConfig = {
  baseUrl: process.env.NEXT_PUBLIC_CORE_API_URL ?? DEFAULT_BASE_URL,
  tenantId: process.env.NEXT_PUBLIC_DEMO_TENANT_ID ?? ""
};

const MISSING_SCOPE_MESSAGE =
  "Set NEXT_PUBLIC_DEMO_TENANT_ID to view tenant-scoped internal support operations.";
const FORBIDDEN_MESSAGE =
  "You do not have access to this internal support view (staff support permission and an active support grant are required).";
const NOT_FOUND_MESSAGE = "This support operations view was not found for this tenant context.";
const LOAD_ERROR_MESSAGE = "Could not load internal support operations right now. Please retry shortly.";

function headers(): Record<string, string> {
  const requestHeaders: Record<string, string> = { "Content-Type": "application/json" };
  if (internalSupportConfig.tenantId) {
    requestHeaders["X-Tenant-Id"] = internalSupportConfig.tenantId;
  }
  return requestHeaders;
}

// GET-only fetch. Never accepts a body. Maps backend status into a safe operator message and never echoes
// the raw backend body, tenant ids, resource ids, stack traces, or SQL details.
async function getJson<T>(path: string): Promise<ApiResult<T>> {
  if (!internalSupportConfig.tenantId) {
    return { error: MISSING_SCOPE_MESSAGE };
  }

  let response: Response;
  try {
    response = await fetch(`${internalSupportConfig.baseUrl}${path}`, {
      method: "GET",
      cache: "no-store",
      headers: headers()
    });
  } catch {
    return { error: LOAD_ERROR_MESSAGE };
  }

  // Drain the body in every case so the connection is freed, but only parse + surface it on 200.
  let text = "";
  try {
    text = await response.text();
  } catch {
    text = "";
  }

  if (response.status === 200) {
    try {
      return { data: (text ? JSON.parse(text) : null) as T };
    } catch {
      return { error: LOAD_ERROR_MESSAGE };
    }
  }

  switch (response.status) {
    case 401:
    case 403:
      return { error: FORBIDDEN_MESSAGE };
    case 404:
      return { error: NOT_FOUND_MESSAGE };
    default:
      return { error: LOAD_ERROR_MESSAGE };
  }
}

function tenantBase(): string {
  return `/api/v1/internal/support/tenants/${internalSupportConfig.tenantId}`;
}

export function getSupportOperationsSummary(): Promise<ApiResult<SupportOperationsSummary>> {
  return getJson<SupportOperationsSummary>(`${tenantBase()}/operations/summary`);
}

export function getSupportOperationsTimeline(
  params: TimelinePageParams = {}
): Promise<ApiResult<SupportOperationsTimeline>> {
  const search = new URLSearchParams();
  if (params.page !== undefined) search.set("page", String(params.page));
  if (params.size !== undefined) search.set("size", String(params.size));
  const qs = search.toString();
  return getJson<SupportOperationsTimeline>(`${tenantBase()}/operations/timeline${qs ? `?${qs}` : ""}`);
}

export function getDataRepairOperationsView(
  requestId: string
): Promise<ApiResult<DataRepairOperationsView>> {
  return getJson<DataRepairOperationsView>(
    `${tenantBase()}/data-repair-requests/${encodeURIComponent(requestId)}/operations-view`
  );
}
