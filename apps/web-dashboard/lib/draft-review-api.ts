import { dashboardCoreApiBaseUrl, enrichDashboardRequestInit, isDashboardApiAuthorityAvailable } from "./api-transport";
import { dashboardApiFetch } from "./dashboard-http";
import { demoTenantId } from "./frontend-authority.mjs";

// OP-CAP-09C Operator Draft Review API client.
// Typed, tenant-scoped helpers over the existing 09B backend endpoints. Internal draft review only:
// no final approval, no external/ERP/connector execution, no master-data mutation. The backend remains authoritative.

export type ApiResult<T> = {
  data: T;
  error?: string;
};

export type DraftReviewLine = {
  lineId: string;
  lineNumber: number;
  productId?: string;
  rawSku?: string;
  normalizedSku?: string;
  productName?: string;
  description?: string;
  quantity?: number | string;
  uom?: string;
  unitPrice?: number | string;
  discountPercent?: number | string;
  lineTotal?: number | string;
  marginPercent?: number | string;
  status: string;
  validationStatus: string;
};

// Superset shape covering both DraftQuoteDetail and DraftOrderDetail (order omits the quote-only fields,
// which are simply absent/undefined). No raw AI result JSON / document text / message text is ever exposed.
export type DraftReviewDetail = {
  draftId: string;
  sourceReviewCaseId?: string;
  sourceValidationRunId?: string;
  customerAccountId?: string;
  customerDisplayName?: string;
  status: string;
  validationStatus?: string;
  requiresHumanReview?: boolean;
  currency?: string;
  subtotalAmount?: number | string;
  discountAmount?: number | string;
  totalAmount?: number | string;
  marginPercent?: number | string;
  lineCount: number;
  lines: DraftReviewLine[];
  externalExecution: string;
  createdAt: string;
};

// Only the bounded fields the 09B PATCH endpoint accepts.
export type DraftLineCorrection = {
  quantity?: number | string;
  uom?: string;
  description?: string;
  unitPrice?: number | string;
  productId?: string;
  correctionReason?: string;
};

// OP-CAP-09D bounded queue summary (no full line arrays, no raw AI/document/message payloads).
export type DraftReviewSummary = {
  draftId: string;
  draftType: string;
  status: string;
  sourceReviewCaseId?: string;
  sourceValidationRunId?: string;
  customerAccountId?: string;
  customerName?: string;
  lineCount: number;
  subtotalAmount?: number | string;
  totalAmount?: number | string;
  currency?: string;
  createdAt: string;
  updatedAt?: string;
  externalExecution: string;
  nextAction: string;
};

export type DraftReviewQueueParams = {
  status?: string;
  sourceReviewCaseId?: string;
  customerRef?: string;
  limit?: number;
};

// OP-CAP-09D read-only product picker item. No cost/margin/supplier/private fields.
export type ProductPickerItem = {
  productId: string;
  sku: string;
  name: string;
  normalizedSku?: string;
  status?: string;
};

const DEFAULT_BASE_URL = "http://localhost:8080";

export const draftReviewConfig = {
  baseUrl: dashboardCoreApiBaseUrl(),
  tenantId: demoTenantId()
};

function headers() {
  const requestHeaders: Record<string, string> = { "Content-Type": "application/json" };
  if (draftReviewConfig.tenantId) {
    requestHeaders["X-Tenant-Id"] = draftReviewConfig.tenantId;
  }
  return requestHeaders;
}

async function requestJson<T>(path: string, init?: RequestInit, fallbackData?: T): Promise<ApiResult<T>> {
  if (!isDashboardApiAuthorityAvailable(draftReviewConfig.tenantId)) {
    return { data: fallbackData as T, error: "Authenticated dashboard access is unavailable." };
  }

  try {
    const response = await dashboardApiFetch(
      path,
      enrichDashboardRequestInit({
        cache: "no-store",
        ...init,
        headers: { ...headers(), ...(init?.headers ?? {}) }
      })
    );
    const text = await response.text();
    const data = text ? (JSON.parse(text) as T) : (fallbackData as T);

    if (!response.ok) {
      if (response.status === 403) {
        return { data, error: "You do not have permission for this draft review action (REVIEW_READ / REVIEW_ACTION required)." };
      }
      const message = typeof data === "object" && data && "message" in data ? String((data as { message?: string }).message) : text;
      return { data, error: message || `Core API returned ${response.status}.` };
    }

    return { data };
  } catch (error) {
    return {
      data: fallbackData as T,
      error: error instanceof Error ? error.message : "Core API is not reachable."
    };
  }
}

// Strip undefined fields so the PATCH body only carries fields the operator actually edited.
function correctionBody(payload: DraftLineCorrection): string {
  const body: Record<string, unknown> = {};
  if (payload.quantity !== undefined && payload.quantity !== "") body.quantity = payload.quantity;
  if (payload.uom !== undefined && payload.uom !== "") body.uom = payload.uom;
  if (payload.description !== undefined) body.description = payload.description;
  if (payload.unitPrice !== undefined && payload.unitPrice !== "") body.unitPrice = payload.unitPrice;
  if (payload.productId !== undefined && payload.productId !== "") body.productId = payload.productId;
  if (payload.correctionReason !== undefined && payload.correctionReason !== "") body.correctionReason = payload.correctionReason;
  return JSON.stringify(body);
}

// --- Draft quote review ---

export function getDraftQuoteReview(draftQuoteId: string) {
  return requestJson<DraftReviewDetail>(`/api/v1/workspace/draft-quotes/${draftQuoteId}/review`, { method: "GET" });
}

export function updateDraftQuoteLine(draftQuoteId: string, lineId: string, payload: DraftLineCorrection) {
  return requestJson<DraftReviewDetail>(`/api/v1/workspace/draft-quotes/${draftQuoteId}/lines/${lineId}`, {
    method: "PATCH",
    body: correctionBody(payload)
  });
}

export function markDraftQuoteReady(draftQuoteId: string, reason?: string) {
  return requestJson<DraftReviewDetail>(`/api/v1/workspace/draft-quotes/${draftQuoteId}/mark-ready`, {
    method: "POST",
    body: JSON.stringify(reason ? { reason } : {})
  });
}

// --- Draft order review ---

export function getDraftOrderReview(draftOrderId: string) {
  return requestJson<DraftReviewDetail>(`/api/v1/workspace/draft-orders/${draftOrderId}/review`, { method: "GET" });
}

export function updateDraftOrderLine(draftOrderId: string, lineId: string, payload: DraftLineCorrection) {
  return requestJson<DraftReviewDetail>(`/api/v1/workspace/draft-orders/${draftOrderId}/lines/${lineId}`, {
    method: "PATCH",
    body: correctionBody(payload)
  });
}

export function markDraftOrderReady(draftOrderId: string, reason?: string) {
  return requestJson<DraftReviewDetail>(`/api/v1/workspace/draft-orders/${draftOrderId}/mark-ready`, {
    method: "POST",
    body: JSON.stringify(reason ? { reason } : {})
  });
}

// --- OP-CAP-09D: bounded review queues + read-only product picker ---

function queueQuery(params: DraftReviewQueueParams): string {
  const search = new URLSearchParams();
  if (params.status) search.set("status", params.status);
  if (params.sourceReviewCaseId) search.set("sourceReviewCaseId", params.sourceReviewCaseId);
  if (params.customerRef) search.set("customerRef", params.customerRef);
  if (params.limit) search.set("limit", String(params.limit));
  const qs = search.toString();
  return qs ? `?${qs}` : "";
}

export function getDraftQuoteReviewQueue(params: DraftReviewQueueParams = {}) {
  return requestJson<DraftReviewSummary[]>(`/api/v1/workspace/draft-quotes/review-queue${queueQuery(params)}`, { method: "GET" }, []);
}

export function getDraftOrderReviewQueue(params: DraftReviewQueueParams = {}) {
  return requestJson<DraftReviewSummary[]>(`/api/v1/workspace/draft-orders/review-queue${queueQuery(params)}`, { method: "GET" }, []);
}

export function searchWorkspaceProducts(q: string, limit = 10) {
  return requestJson<ProductPickerItem[]>(`/api/v1/workspace/products/search?q=${encodeURIComponent(q)}&limit=${limit}`, { method: "GET" }, []);
}
