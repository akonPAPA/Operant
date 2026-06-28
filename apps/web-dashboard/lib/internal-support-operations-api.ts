// OP-CAP-56/57 — Internal Support API client (READ-ONLY).
//
// OP-CAP-55 backend endpoints:
//   GET /api/v1/internal/support/tenants/search?query=&page=&size=                  (OP-CAP-57 locator)
//   GET /api/v1/internal/support/tenants/{tenantId}/support-context                 (OP-CAP-57 JIT boundary)
//   GET /api/v1/internal/support/tenants/{tenantId}/operations/summary
//   GET /api/v1/internal/support/tenants/{tenantId}/operations/timeline
//   GET /api/v1/internal/support/tenants/{tenantId}/data-repair-requests/{requestId}/operations-view
//
// Data-boundary law (mirrors the backend contract):
//  - This client only ever performs GET. It builds NO request body, so it cannot smuggle
//    tenant/actor/staff/status/approval/execution/audit authority fields into a payload.
//  - OP-CAP-57 replaces the demo-tenant env assumption: the SELECTED tenant is a navigation/resource
//    handle passed per call. It is NOT authority — the backend re-resolves the staff actor and re-validates
//    an active support grant for that tenant on every call. For tenant-scoped reads it is sent as the
//    `X-Tenant-Id` header (the backend requires path == header tenant and fails closed otherwise). The
//    cross-tenant locator search sends no tenant header at all.
//  - The only client-supplied parameters are safe route/path locators (selected tenantId, data-repair
//    requestId) and safe bounded pagination/search params (query/page/size) the backend contract requires.
//  - Errors are mapped to safe operator messages; the raw backend body is drained and never surfaced.

export type ApiResult<T> = {
  data?: T;
  error?: string;
};

// --- OP-CAP-57 locator + support-context types ---

// Mirrors SupportTenantLocatorDtos.SupportTenantLocatorResult.
export type SupportTenantLocatorResult = {
  tenantId: string;
  displayName: string;
  slug: string;
  status: string;
  supportScopes: string[];
  grantExpiresAt?: string | null;
  readOnly: boolean;
  externalExecution: string;
};

// Mirrors SupportTenantLocatorDtos.SupportTenantSearchResponse.
export type SupportTenantSearch = {
  query: string;
  page: number;
  pageSize: number;
  returnedCount: number;
  hasMore: boolean;
  results: SupportTenantLocatorResult[];
  generatedAt: string;
};

// Mirrors SupportTenantLocatorDtos.SupportTenantContextResponse.
export type SupportTenantContext = {
  tenantId: string;
  displayName: string;
  slug: string;
  status: string;
  supportScopes: string[];
  grantExpiresAt?: string | null;
  readOnly: boolean;
  canViewOperations: boolean;
  externalExecution: string;
  generatedAt: string;
};

// --- OP-CAP-55 operations types ---

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

export type SupportOperationsTimelineEntry = {
  category: string;
  eventType: string;
  referenceId: string;
  status: string;
  occurredAt: string;
};

export type SupportOperationsTimeline = {
  tenantId: string;
  page: number;
  pageSize: number;
  returnedCount: number;
  hasMore: boolean;
  entries: SupportOperationsTimelineEntry[];
  generatedAt: string;
};

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

export type TenantSearchParams = {
  page?: number;
  size?: number;
};

const DEFAULT_BASE_URL = "http://localhost:8080";

export const internalSupportConfig = {
  baseUrl: process.env.NEXT_PUBLIC_CORE_API_URL ?? DEFAULT_BASE_URL
};

const NO_TENANT_MESSAGE = "Select a tenant from the internal support locator to open this view.";
const FORBIDDEN_MESSAGE =
  "You do not have an active support grant for this tenant (staff support permission and an approved, unexpired grant are required).";
const NOT_FOUND_MESSAGE = "This support view was not found for this tenant context.";
const LOAD_ERROR_MESSAGE = "Could not load internal support data right now. Please retry shortly.";

// GET-only fetch. `tenantId` (when provided) is sent ONLY as the X-Tenant-Id resource-scope header; it is
// never placed in a request body. Maps backend status into a safe operator message and never echoes the raw
// backend body, tenant ids, resource ids, stack traces, or SQL details.
async function getJson<T>(path: string, tenantId?: string): Promise<ApiResult<T>> {
  const requestHeaders: Record<string, string> = { "Content-Type": "application/json" };
  if (tenantId) {
    requestHeaders["X-Tenant-Id"] = tenantId;
  }

  let response: Response;
  try {
    response = await fetch(`${internalSupportConfig.baseUrl}${path}`, {
      method: "GET",
      cache: "no-store",
      headers: requestHeaders
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

function tenantBase(tenantId: string): string {
  return `/api/v1/internal/support/tenants/${encodeURIComponent(tenantId)}`;
}

// --- OP-CAP-57 locator (cross-tenant: NO X-Tenant-Id header) ---

export function searchSupportTenants(
  query: string,
  params: TenantSearchParams = {}
): Promise<ApiResult<SupportTenantSearch>> {
  const search = new URLSearchParams();
  if (query) search.set("query", query);
  if (params.page !== undefined) search.set("page", String(params.page));
  if (params.size !== undefined) search.set("size", String(params.size));
  const qs = search.toString();
  return getJson<SupportTenantSearch>(`/api/v1/internal/support/tenants/search${qs ? `?${qs}` : ""}`);
}

export function getSupportTenantContext(tenantId: string): Promise<ApiResult<SupportTenantContext>> {
  if (!tenantId) return Promise.resolve({ error: NO_TENANT_MESSAGE });
  return getJson<SupportTenantContext>(`${tenantBase(tenantId)}/support-context`, tenantId);
}

// --- OP-CAP-55 operations (tenant-scoped by the SELECTED tenant handle) ---

export function getSupportOperationsSummary(
  tenantId: string
): Promise<ApiResult<SupportOperationsSummary>> {
  if (!tenantId) return Promise.resolve({ error: NO_TENANT_MESSAGE });
  return getJson<SupportOperationsSummary>(`${tenantBase(tenantId)}/operations/summary`, tenantId);
}

export function getSupportOperationsTimeline(
  tenantId: string,
  params: TimelinePageParams = {}
): Promise<ApiResult<SupportOperationsTimeline>> {
  if (!tenantId) return Promise.resolve({ error: NO_TENANT_MESSAGE });
  const search = new URLSearchParams();
  if (params.page !== undefined) search.set("page", String(params.page));
  if (params.size !== undefined) search.set("size", String(params.size));
  const qs = search.toString();
  return getJson<SupportOperationsTimeline>(
    `${tenantBase(tenantId)}/operations/timeline${qs ? `?${qs}` : ""}`,
    tenantId
  );
}

export function getDataRepairOperationsView(
  tenantId: string,
  requestId: string
): Promise<ApiResult<DataRepairOperationsView>> {
  if (!tenantId) return Promise.resolve({ error: NO_TENANT_MESSAGE });
  return getJson<DataRepairOperationsView>(
    `${tenantBase(tenantId)}/data-repair-requests/${encodeURIComponent(requestId)}/operations-view`,
    tenantId
  );
}
