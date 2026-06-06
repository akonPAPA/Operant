// OP-CAP-06C RFQ Handoff Operator Workflow client.
// Surfaces the controlled channel/bot RFQ handoff review workflow (OP-CAP-06B record + OP-CAP-06C
// operator transitions). A handoff is a reviewable draft request only — never a quote/order.
// Read and transition endpoints live under /api/v1/channels and require ADMIN_SETTINGS_READ.
// No secrets, raw tokens, or raw provider payloads are requested. No quote/order/ERP action exists.

export type RfqHandoffStatus = "PENDING_REVIEW" | "IN_REVIEW" | "CONVERTED" | "DISMISSED";

export type RfqHandoff = {
  id: string;
  inboundChannelEventId: string;
  channelConnectionId: string;
  sourceChannel: string;
  sourceExternalEventId?: string;
  sourceActorExternalId?: string;
  customerAccountId?: string;
  customerContactId?: string;
  requestText?: string;
  requestPreview?: string;
  detectedIntent?: string;
  status: RfqHandoffStatus;
  reviewerUserId?: string;
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

const DEFAULT_BASE_URL = "http://localhost:8080";

export const rfqHandoffClient = {
  baseUrl: process.env.CORE_API_BASE_URL ?? process.env.NEXT_PUBLIC_CORE_API_URL ?? DEFAULT_BASE_URL,
  tenantId: process.env.NEXT_PUBLIC_DEMO_TENANT_ID ?? ""
};

// All /api/v1/channels endpoints (reads and the OP-CAP-06C transition POSTs) are guarded by
// ADMIN_SETTINGS_READ in the backend ApiPermissionInterceptor, which re-validates on every request.
// This is an operator-only surface; the bot/channel path can never reach these transition endpoints.
const CHANNELS_PERMISSION = "ADMIN_SETTINGS_READ";

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
      error: "Set NEXT_PUBLIC_DEMO_TENANT_ID to read tenant-scoped RFQ handoff data."
    };
  }
  try {
    const response = await fetch(`${rfqHandoffClient.baseUrl}${path}`, {
      cache: "no-store",
      ...init,
      headers: { ...baseHeaders(), ...((init.headers as Record<string, string>) ?? {}) }
    });
    const text = await response.text();
    const data = text ? (JSON.parse(text) as T) : fallback;
    if (!response.ok) {
      const message =
        typeof data === "object" && data && "message" in data
          ? String((data as { message?: string }).message)
          : "";
      return { data: fallback, error: message || `Core API returned ${response.status}.` };
    }
    return { data };
  } catch (error) {
    return { data: fallback, error: error instanceof Error ? error.message : "Core API is not reachable." };
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
export function startReviewRfqHandoff(id: string, reviewerUserId?: string) {
  return request<RfqHandoff | null>(
    `/api/v1/channels/rfq-handoffs/${id}/start-review`,
    { method: "POST", body: JSON.stringify({ reviewerUserId: reviewerUserId ?? null }) },
    null
  );
}

/** Dismiss an invalid/irrelevant handoff. Reason is required (non-blank) by the backend. */
export function dismissRfqHandoff(id: string, reason: string, actorUserId?: string) {
  return request<RfqHandoff | null>(
    `/api/v1/channels/rfq-handoffs/${id}/dismiss`,
    { method: "POST", body: JSON.stringify({ reason, actorUserId: actorUserId ?? null }) },
    null
  );
}

/** Mark a handoff converted (workflow complete). Does NOT create any quote/order. */
export function markConvertedRfqHandoff(id: string, conversionNote?: string, actorUserId?: string) {
  return request<RfqHandoff | null>(
    `/api/v1/channels/rfq-handoffs/${id}/mark-converted`,
    { method: "POST", body: JSON.stringify({ conversionNote: conversionNote ?? null, actorUserId: actorUserId ?? null }) },
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
