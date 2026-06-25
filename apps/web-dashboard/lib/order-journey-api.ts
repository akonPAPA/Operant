// OP-CAP-22 Order Journey & Fulfillment Visibility client.
// Read-only, tenant-scoped views of the commercial transaction lifecycle: list, attention queue,
// and journey detail (ordered milestones, recent events, fulfillment signals). Reads require
// ANALYTICS_READ (re-validated by the backend ApiPermissionInterceptor on every request).
// The frontend never invents journey statuses, never shows fake carrier/GPS/payment data, and
// never mutates business data. Payment milestones are surfaced exactly as the backend reports them
// (UNKNOWN until a real payment mirror exists). No secrets or raw payloads are exposed.

export type OrderJourneyListItem = {
  id: string;
  sourceType: string;
  sourceId: string;
  customerAccountId: string | null;
  customerDisplayName: string | null;
  currentStage: string;
  currentStatus: string;
  riskLevel: string;
  blocked: boolean;
  evidenceLevel: string;
  lastSignalAt: string | null;
  updatedAt: string;
};

export type OrderJourneySummary = {
  items: OrderJourneyListItem[];
  total: number;
  blockedCount: number;
  previewLimit: number;
  partial: boolean;
  generatedAt: string;
};

export type OrderJourneyMilestone = {
  milestoneCode: string;
  milestoneLabel: string;
  milestoneState: string;
  evidenceLevel: string;
  occurredAt: string | null;
  estimatedAt: string | null;
  sourceType: string | null;
  sourceRef: string | null;
  customerVisible: boolean;
  sortOrder: number;
};

export type OrderJourneyEvent = {
  eventType: string;
  eventStatus: string | null;
  evidenceLevel: string;
  message: string;
  sourceType: string | null;
  sourceRef: string | null;
  actorType: string;
  customerVisible: boolean;
  occurredAt: string;
};

export type FulfillmentSignal = {
  id: string;
  sourceType: string;
  signalType: string;
  signalStatus: string | null;
  confidence: number | string | null;
  sourceRef: string | null;
  customerVisible: boolean;
  receivedAt: string;
  processedAt: string | null;
};

export type OrderJourneyDetail = {
  id: string;
  sourceType: string;
  sourceId: string;
  customerAccountId: string | null;
  customerDisplayName: string | null;
  currentStage: string;
  currentStatus: string;
  riskLevel: string;
  blocked: boolean;
  customerVisibleStatus: string;
  internalStatus: string;
  lastSignalAt: string | null;
  createdAt: string;
  updatedAt: string;
  milestones: OrderJourneyMilestone[];
  recentEvents: OrderJourneyEvent[];
  fulfillmentSignals: FulfillmentSignal[];
  paymentStatusAvailable: boolean;
  fulfillmentTrackingConnected: boolean;
  // OP-CAP-23: how this projection was obtained — "READY" (already-projected, the production path) or
  // "ON_READ_FALLBACK" (materialized during this read as the documented temporary fallback). Optional so
  // older payloads remain compatible. The frontend only reports this honestly; it never invents status.
  projectionSource?: string | null;
  generatedAt: string;
};

export type JourneyProjectionHealth = {
  pendingEvents: number;
  failedEvents: number;
  deadLetteredEvents: number;
  failedCheckpoints: number;
  lastProcessedAt: string | null;
  generatedAt: string;
};

export type OrderJourneyListResult = { data: OrderJourneySummary | null; error?: string };
export type OrderJourneyDetailResult = { data: OrderJourneyDetail | null; error?: string };
export type JourneyProjectionHealthResult = { data: JourneyProjectionHealth | null; error?: string };

// OP-CAP-46D — operator-minted secure tracking link result. Mirrors the safe backend DTO:
// the raw token is embedded once in `trackingPath` for one-shot sharing, and `expiresAt` is
// the ISO-8601 expiry. No tenantId, journeyId, token hash, or internal link id is carried.
export type TrackingLinkCreated = {
  trackingPath: string;
  expiresAt: string;
};

const DEFAULT_BASE_URL = "http://localhost:8080";
const ANALYTICS_READ = "ANALYTICS_READ";
const REVIEW_ACTION = "REVIEW_ACTION";

export const orderJourneyClient = {
  baseUrl: process.env.CORE_API_BASE_URL ?? process.env.NEXT_PUBLIC_CORE_API_URL ?? DEFAULT_BASE_URL,
  tenantId: process.env.NEXT_PUBLIC_DEMO_TENANT_ID ?? ""
};

function baseHeaders(): Record<string, string> {
  const h: Record<string, string> = {
    "Content-Type": "application/json",
    "X-OrderPilot-Permissions": ANALYTICS_READ
  };
  if (orderJourneyClient.tenantId) {
    h["X-Tenant-Id"] = orderJourneyClient.tenantId;
  }
  return h;
}

async function read<T>(path: string): Promise<{ data: T | null; error?: string }> {
  if (!orderJourneyClient.tenantId) {
    return { data: null, error: "Set NEXT_PUBLIC_DEMO_TENANT_ID to read tenant-scoped order journeys." };
  }
  try {
    const response = await fetch(`${orderJourneyClient.baseUrl}${path}`, {
      cache: "no-store",
      method: "GET",
      headers: baseHeaders()
    });
    const text = await response.text();
    if (!response.ok) {
      let message = "";
      if (text) {
        try {
          message = (JSON.parse(text) as { message?: string }).message ?? "";
        } catch {
          message = "";
        }
      }
      return { data: null, error: message || `Core API returned ${response.status}.` };
    }
    return { data: text ? (JSON.parse(text) as T) : null };
  } catch {
    return { data: null, error: "Core API not reachable." };
  }
}

export async function getOrderJourneys(): Promise<OrderJourneyListResult> {
  return read<OrderJourneySummary>("/api/v1/order-journeys");
}

export async function getOrderJourneyAttention(): Promise<OrderJourneyListResult> {
  return read<OrderJourneySummary>("/api/v1/order-journeys/attention");
}

export async function getOrderJourney(id: string): Promise<OrderJourneyDetailResult> {
  return read<OrderJourneyDetail>(`/api/v1/order-journeys/${encodeURIComponent(id)}`);
}

// OP-CAP-23: bounded, read-only projector health (pending/failed/dead-lettered counts + last processed).
export async function getJourneyProjectionHealth(): Promise<JourneyProjectionHealthResult> {
  return read<JourneyProjectionHealth>("/api/v1/order-journeys/projection-health");
}

// OP-CAP-46D — operator mints a one-time secure customer tracking link. The request body
// carries business intent only (optional TTL); tenant comes from `X-Tenant-Id` and the actor
// is server-resolved from the trusted header. The journey id is in the path.
//
// Non-2xx responses are turned into thrown errors with the HTTP `status` attached so the caller
// can map them to operator-safe messages through `mapOperatorActionError` — the raw backend
// body is intentionally drained and never surfaced (it may carry internal ids/metadata).
//
// The raw token is embedded in `trackingPath` exactly once and is NEVER persisted client-side
// (no localStorage / sessionStorage), NEVER logged, and NEVER sent to analytics.
export async function createOrderJourneyTrackingLink(
  journeyId: string,
  expiresInHours?: number
): Promise<TrackingLinkCreated> {
  if (!orderJourneyClient.tenantId) {
    throw Object.assign(new Error("Tenant scope is not configured."), { status: 0 });
  }
  const body: Record<string, number> = {};
  if (typeof expiresInHours === "number" && Number.isFinite(expiresInHours)) {
    body.expiresInHours = expiresInHours;
  }
  let response: Response;
  try {
    response = await fetch(
      `${orderJourneyClient.baseUrl}/api/v1/order-journeys/${encodeURIComponent(journeyId)}/tracking-links`,
      {
        method: "POST",
        cache: "no-store",
        headers: {
          "Content-Type": "application/json",
          "X-OrderPilot-Permissions": REVIEW_ACTION,
          "X-Tenant-Id": orderJourneyClient.tenantId
        },
        body: JSON.stringify(body)
      }
    );
  } catch {
    throw Object.assign(new Error("Core API is not reachable."), { status: 0 });
  }
  if (!response.ok) {
    // Drain the body so the socket can be reused; never surface it — backend error bodies may
    // reference internal ids / tenant scoping that must not reach the operator UI.
    try {
      await response.text();
    } catch {
      // ignore
    }
    throw Object.assign(new Error(`Core API returned ${response.status}.`), { status: response.status });
  }
  const text = await response.text();
  const parsed = text ? (JSON.parse(text) as Partial<TrackingLinkCreated>) : null;
  if (!parsed || typeof parsed.trackingPath !== "string" || typeof parsed.expiresAt !== "string") {
    throw Object.assign(new Error("Tracking link response was malformed."), { status: 500 });
  }
  return { trackingPath: parsed.trackingPath, expiresAt: parsed.expiresAt };
}

// OP-CAP-46F — Customer-Facing Tracking URL Mapping.
//
// The backend mint response (`TrackingLinkCreated.trackingPath`) carries the BACKEND public API
// path `/api/v1/public/order-tracking/{token}`. Operators must share the CUSTOMER-FACING frontend
// page `/public/order-tracking/{token}` instead — the API endpoint is not a human-facing page.
//
// These helpers are pure (no `window`, no storage, no logging). They preserve the opaque token
// exactly as a single path segment via strict prefix matching — never a fragile string replace
// that could rewrite an unrelated path. A path that does not match the expected backend (or the
// already-frontend) prefix, or that has an empty/multi-segment token, returns `null` so the UI can
// fall back to a safe error instead of ever displaying the backend API path as a share link.
const BACKEND_TRACKING_PREFIX = "/api/v1/public/order-tracking/";
const CUSTOMER_TRACKING_PREFIX = "/public/order-tracking/";

// Extract the single opaque token segment from `path` given `prefix`, or `null` if the remainder
// is empty or contains a `/` (the token must be exactly one path segment — no traversal, no extra
// path). The token is returned verbatim; it is NOT re-encoded (it is already a URL path segment).
function trackingTokenSegment(path: string, prefix: string): string | null {
  if (!path.startsWith(prefix)) {
    return null;
  }
  const token = path.slice(prefix.length);
  if (!token || token.includes("/")) {
    return null;
  }
  return token;
}

// Map a backend `trackingPath` to the customer-facing frontend tracking path
// `/public/order-tracking/{token}`. Accepts an already-frontend path unchanged. Returns `null`
// for any malformed/unexpected shape so the caller can show a safe error.
export function toCustomerTrackingPath(trackingPath: string): string | null {
  if (typeof trackingPath !== "string") {
    return null;
  }
  const trimmed = trackingPath.trim();
  // Already a customer-facing path — normalize (validate the token segment) and return it.
  const frontendToken = trackingTokenSegment(trimmed, CUSTOMER_TRACKING_PREFIX);
  if (frontendToken) {
    return `${CUSTOMER_TRACKING_PREFIX}${frontendToken}`;
  }
  const backendToken = trackingTokenSegment(trimmed, BACKEND_TRACKING_PREFIX);
  if (backendToken) {
    return `${CUSTOMER_TRACKING_PREFIX}${backendToken}`;
  }
  return null;
}

// Build a shareable customer-facing href. When a browser `origin` is supplied (client-only code),
// the result is absolute (`https://host/public/order-tracking/{token}`); otherwise it is the
// relative path. Returns `null` for a malformed `trackingPath`. Pure — the caller is responsible
// for safely reading `window.location.origin` only in client code.
export function toCustomerTrackingHref(trackingPath: string, origin?: string): string | null {
  const path = toCustomerTrackingPath(trackingPath);
  if (!path) {
    return null;
  }
  if (origin) {
    return `${origin.replace(/\/+$/, "")}${path}`;
  }
  return path;
}
