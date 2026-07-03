import { demoTenantId } from "./frontend-authority.mjs";

// OP-CAP-06C RFQ Handoff Operator Workflow client.
// Surfaces the controlled channel/bot RFQ handoff review workflow (OP-CAP-06B record + OP-CAP-06C
// operator transitions). A handoff is a reviewable draft request only — never a quote/order.
// Read and transition endpoints live under /api/v1/channels and require ADMIN_SETTINGS_READ.
// No secrets, raw tokens, or raw provider payloads are requested. No quote/order/ERP action exists.

import type { AiWorkSuggestion, AiWorkType } from "./ai-work-api";

export type RfqHandoffStatus = "PENDING_REVIEW" | "IN_REVIEW" | "CONVERTED" | "DISMISSED";

// Operator-safe shape mirroring the backend ChannelRfqHandoffResponse. Internal actor and raw
// source/correlation identifiers (reviewerUserId, inboundChannelEventId, channelConnectionId,
// sourceExternalEventId) are intentionally NOT part of this contract.
export type RfqHandoff = {
  id: string;
  sourceChannel: string;
  sourceActorExternalId?: string;
  customerAccountId?: string;
  customerContactId?: string;
  requestText?: string;
  requestPreview?: string;
  detectedIntent?: string;
  status: RfqHandoffStatus;
  reviewStartedAt?: string;
  dismissedAt?: string;
  dismissReason?: string;
  convertedAt?: string;
  conversionNote?: string;
  createdAt: string;
  updatedAt: string;
};

export type RfqHandoffApiResult<T> = {
  data: T;
  error?: string;
};

export type RfqHandoffDraftQuote = {
  handoff: RfqHandoff;
  draftQuote: {
    id: string;
    quoteNumber: string;
    sourceType: string;
    customerDisplayName?: string;
    status: string;
    validationStatus: string;
    requiresHumanReview: boolean;
    currency: string;
    subtotalAmount: number;
    discountAmount: number;
    totalAmount: number;
    createdAt: string;
  };
  auditStatus: "RECORDED";
  outboxStatus: "NOT_REQUESTED";
  externalWriteSafety: "NO_EXTERNAL_WRITE";
};

const DEFAULT_BASE_URL = "http://localhost:8080";

export const rfqHandoffClient = {
  baseUrl: process.env.CORE_API_BASE_URL ?? process.env.NEXT_PUBLIC_CORE_API_URL ?? DEFAULT_BASE_URL,
  tenantId: demoTenantId()
};

// All /api/v1/channels endpoints (reads and the OP-CAP-06C transition POSTs) are guarded by
// ADMIN_SETTINGS_READ in the backend ApiPermissionInterceptor, which re-validates on every request.
// This is an operator-only surface; the bot/channel path can never reach these transition endpoints.
const CHANNELS_PERMISSION = "ADMIN_SETTINGS_READ";
const AI_WORK_ACTION = "AI_WORK_ACTION";
const QUOTE_ACTION = "QUOTE_ACTION";

function rfqHandoffStatusMessage(status: number): string {
  switch (status) {
    case 400:
    case 422:
      return "This RFQ action is not available in its current state.";
    case 403:
      return "You do not have access to this RFQ workspace.";
    case 404:
      return "This RFQ handoff is not found or no longer available.";
    case 409:
      return "This RFQ action conflicts with a newer workflow update.";
    default:
      return "The RFQ action could not be completed.";
  }
}

function baseHeaders(): Record<string, string> {
  const h: Record<string, string> = {
    "Content-Type": "application/json",
    "X-OrderPilot-Permissions": CHANNELS_PERMISSION
  };
  if (rfqHandoffClient.tenantId) {
    h["X-Tenant-Id"] = rfqHandoffClient.tenantId;
  }
  return h;
}

async function request<T>(path: string, init: RequestInit, fallback: T): Promise<RfqHandoffApiResult<T>> {
  if (!rfqHandoffClient.tenantId) {
    return {
      data: fallback,
      error: "Authenticated dashboard access is unavailable."
    };
  }
  try {
    const response = await fetch(`${rfqHandoffClient.baseUrl}${path}`, {
      cache: "no-store",
      ...init,
      headers: { ...baseHeaders(), ...((init.headers as Record<string, string>) ?? {}) }
    });
    if (!response.ok) {
      // Never surface raw backend bodies; they can contain internal resource or policy details.
      return { data: fallback, error: rfqHandoffStatusMessage(response.status) };
    }
    const text = await response.text();
    const data = text ? (JSON.parse(text) as T) : fallback;
    return { data };
  } catch {
    return { data: fallback, error: "Core API is not reachable." };
  }
}

// --- Read ---

export function listRfqHandoffs(status?: RfqHandoffStatus) {
  const query = status ? `?status=${encodeURIComponent(status)}` : "";
  return request<RfqHandoff[]>(`/api/v1/channels/rfq-handoffs${query}`, { method: "GET" }, []);
}

export function getRfqHandoff(id: string) {
  return request<RfqHandoff | null>(`/api/v1/channels/rfq-handoffs/${id}`, { method: "GET" }, null);
}

// --- Operator workflow transitions (tenant-scoped, audited, no quote/order/ERP write) ---

/** Take a handoff into review: PENDING_REVIEW -> IN_REVIEW. */
export function startReviewRfqHandoff(id: string) {
  return request<RfqHandoff | null>(
    `/api/v1/channels/rfq-handoffs/${id}/start-review`,
    { method: "POST", body: JSON.stringify({}) },
    null
  );
}

/** Dismiss an invalid/irrelevant handoff. Reason is required (non-blank) by the backend. */
export function dismissRfqHandoff(id: string, reason: string) {
  return request<RfqHandoff | null>(
    `/api/v1/channels/rfq-handoffs/${id}/dismiss`,
    { method: "POST", body: JSON.stringify({ reason }) },
    null
  );
}

/** Mark a handoff converted (workflow complete). Does NOT create any quote/order. */
export function markConvertedRfqHandoff(id: string, conversionNote?: string) {
  return request<RfqHandoff | null>(
    `/api/v1/channels/rfq-handoffs/${id}/mark-converted`,
    { method: "POST", body: JSON.stringify({ conversionNote: conversionNote ?? null }) },
    null
  );
}

/** Generate an advisory AI suggestion from the selected handoff. Backend resolves source context. */
export function generateRfqHandoffAiSuggestion(
  id: string,
  workType: AiWorkType = "NEXT_ACTION_SUGGESTION"
) {
  const idempotencyKey = `rfq-handoff-${id}-${workType}`;
  return request<AiWorkSuggestion | null>(
    `/api/v1/ai-work/rfq-handoffs/${id}/suggestions`,
    {
      method: "POST",
      headers: {
        "X-OrderPilot-Permissions": AI_WORK_ACTION,
        "Idempotency-Key": idempotencyKey
      },
      body: JSON.stringify({ workType })
    },
    null
  );
}

/**
 * Convert an operator-reviewed handoff into a review-required draft quote.
 * The body is empty: source context, actor, role, tenant, status, and idempotency are backend-owned.
 */
export function createDraftQuoteFromRfqHandoff(id: string) {
  return request<RfqHandoffDraftQuote | null>(
    `/api/v1/quotes/drafts/from-rfq-handoff/${id}`,
    {
      method: "POST",
      headers: { "X-OrderPilot-Permissions": QUOTE_ACTION },
      body: JSON.stringify({})
    },
    null
  );
}

// --- Display helpers ---

export function statusLabel(status?: string): string {
  switch (status) {
    case "PENDING_REVIEW": return "Pending review";
    case "IN_REVIEW": return "In review";
    case "CONVERTED": return "Converted";
    case "DISMISSED": return "Dismissed";
    default: return status ?? "Unknown";
  }
}

export function statusClass(status?: string): string {
  switch (status) {
    case "PENDING_REVIEW": return "warning";
    case "IN_REVIEW": return "";
    case "CONVERTED": return "done";
    case "DISMISSED": return "error";
    default: return "";
  }
}

/** Whether a handoff is still actionable (non-terminal). Terminal: CONVERTED, DISMISSED. */
export function isTerminal(status?: string): boolean {
  return status === "CONVERTED" || status === "DISMISSED";
}
