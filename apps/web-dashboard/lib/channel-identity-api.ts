import { dashboardCoreApiBaseUrl, enrichDashboardRequestInit } from "./api-transport";
import { demoTenantId } from "./frontend-authority.mjs";

// OP-CAP-06E Channel Identity operator control and read contract client.
// Surfaces the hardened OP-CAP-06D backend identity review and mutation surface.
// Mutations require CHANNEL_IDENTITY_ACTION — never BOT_ACTION.
// No auto-linking, no bot or AI direct mutation, no secrets or raw tokens in responses.

export type ChannelIdentityResolutionView = {
  /** Canonical frontend status: RESOLVED | AMBIGUOUS | UNKNOWN | BLOCKED | NOT_APPLICABLE */
  status: "RESOLVED" | "AMBIGUOUS" | "UNKNOWN" | "BLOCKED" | "NOT_APPLICABLE";
  channelIdentityId?: string;
  customerAccountId?: string;
  customerContactId?: string;
  externalSenderId?: string;
  reason?: string;
  updatedAt?: string;
};

export type ChannelIdentity = {
  id: string;
  channelType: string;
  externalSenderId?: string;
  externalConversationId?: string;
  senderPhone?: string;
  senderDisplayName?: string;
  customerAccountId?: string;
  customerContactId?: string;
  /** Raw domain status: LINKED | UNLINKED | BLOCKED | NEEDS_REVIEW | SUGGESTED_MATCH */
  identityStatus: string;
  matchConfidence?: number;
  createdAt: string;
  updatedAt: string;
  linkedAt?: string;
  notes?: string;
  identityResolution?: ChannelIdentityResolutionView;
};

export type ChannelIdentityLinkRequest = {
  customerAccountId?: string;
  customerContactId?: string;
  notes?: string;
};

export type CustomerAccountSummary = {
  id: string;
  accountCode: string;
  legalName: string;
  displayName: string;
  status: string;
};

/** Minimal contact summary for the link flow; direct contact details (email/phone) are excluded. */
export type CustomerContactSummary = {
  id: string;
  customerAccountId: string;
  contactType: string;
  fullName: string;
  preferred: boolean;
  active: boolean;
};

export type ChannelIdentityApiResult<T> = {
  data: T;
  error?: string;
};

const DEFAULT_BASE_URL = "http://localhost:8080";

export const channelIdentityClient = {
  baseUrl: dashboardCoreApiBaseUrl(),
  tenantId: demoTenantId()
};

// Mutations on /api/v1/channel-identities require CHANNEL_IDENTITY_ACTION, not BOT_ACTION.
// The backend ApiPermissionInterceptor re-validates this on every request.
const CHANNEL_IDENTITY_ACTION = "CHANNEL_IDENTITY_ACTION";

function baseHeaders(extra?: Record<string, string>): Record<string, string> {
  const h: Record<string, string> = { "Content-Type": "application/json" };
  if (channelIdentityClient.tenantId) {
    h["X-Tenant-Id"] = channelIdentityClient.tenantId;
  }
  return { ...h, ...(extra ?? {}) };
}

async function request<T>(
  path: string,
  init: RequestInit,
  fallback: T
): Promise<ChannelIdentityApiResult<T>> {
  if (!channelIdentityClient.tenantId) {
    return {
      data: fallback,
      error: "Authenticated dashboard access is unavailable."
    };
  }
  try {
    const response = await fetch(
      `${channelIdentityClient.baseUrl}${path}`,
      enrichDashboardRequestInit({
        cache: "no-store",
        ...init,
        headers: {
          ...baseHeaders(),
          ...((init.headers as Record<string, string>) ?? {})
        }
      })
    );
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
    return {
      data: fallback,
      error: error instanceof Error ? error.message : "Core API is not reachable."
    };
  }
}

// --- Read ---

export function listChannelIdentities() {
  return request<ChannelIdentity[]>("/api/v1/channel-identities", { method: "GET" }, []);
}

export function getChannelIdentity(id: string) {
  return request<ChannelIdentity | null>(`/api/v1/channel-identities/${id}`, { method: "GET" }, null);
}

export function listCustomerAccounts() {
  return request<CustomerAccountSummary[]>("/api/v1/customers", { method: "GET" }, []);
}

export function listCustomerContacts(customerId: string) {
  return request<CustomerContactSummary[]>(
    `/api/v1/customers/${customerId}/contacts`,
    { method: "GET" },
    []
  );
}

// --- Mutations (all require CHANNEL_IDENTITY_ACTION) ---

/** Confirm/link this sender to a tenant-owned customer account and/or contact. */
export function linkChannelIdentity(id: string, req: ChannelIdentityLinkRequest) {
  return request<ChannelIdentity | null>(
    `/api/v1/channel-identities/${id}/link`,
    {
      method: "POST",
      headers: { "X-OrderPilot-Permissions": CHANNEL_IDENTITY_ACTION },
      body: JSON.stringify(req)
    },
    null
  );
}

/** Unlink/reset this sender back to UNLINKED. Idempotent if already unlinked. */
export function unlinkChannelIdentity(id: string, notes?: string) {
  return request<ChannelIdentity | null>(
    `/api/v1/channel-identities/${id}/unlink`,
    {
      method: "POST",
      headers: { "X-OrderPilot-Permissions": CHANNEL_IDENTITY_ACTION },
      body: JSON.stringify({ notes: notes ?? "" })
    },
    null
  );
}

/** Block this sender. Bot runtime will not process messages from a blocked sender. */
export function blockChannelIdentity(id: string, notes?: string) {
  return request<ChannelIdentity | null>(
    `/api/v1/channel-identities/${id}/block`,
    {
      method: "POST",
      headers: { "X-OrderPilot-Permissions": CHANNEL_IDENTITY_ACTION },
      body: JSON.stringify({ notes: notes ?? "" })
    },
    null
  );
}

/** Mark this sender as needing manual review. Bot runtime routes them through AMBIGUOUS path. */
export function markNeedsReview(id: string, notes?: string) {
  return request<ChannelIdentity | null>(
    `/api/v1/channel-identities/${id}/needs-review`,
    {
      method: "POST",
      headers: { "X-OrderPilot-Permissions": CHANNEL_IDENTITY_ACTION },
      body: JSON.stringify({ notes: notes ?? "" })
    },
    null
  );
}

// --- Display helpers ---

/** Shorten a long external sender ID for display. Never truncates UUIDs under 36 chars. */
export function formatSenderId(senderId?: string): string {
  if (!senderId) return "n/a";
  if (senderId.length <= 20) return senderId;
  return `${senderId.slice(0, 8)}…${senderId.slice(-6)}`;
}

export function resolutionStatusLabel(status?: string): string {
  switch (status) {
    case "RESOLVED": return "Linked";
    case "AMBIGUOUS": return "Needs review";
    case "UNKNOWN": return "Unlinked";
    case "BLOCKED": return "Blocked";
    case "NOT_APPLICABLE": return "N/A";
    default: return status ?? "Unknown";
  }
}

export function resolutionStatusClass(status?: string): string {
  switch (status) {
    case "RESOLVED": return "done";
    case "AMBIGUOUS": return "warning";
    case "UNKNOWN": return "";
    case "BLOCKED": return "error";
    default: return "";
  }
}
