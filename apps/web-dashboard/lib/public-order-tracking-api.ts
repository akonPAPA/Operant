import { coreApiBaseUrl } from "@/lib/core-api-client";

// OP-CAP-46E — public, UNAUTHENTICATED customer tracking client.
//
// This module is deliberately separate from the tenant-scoped dashboard clients. The public
// tracking endpoint derives the entire (tenant, journey) scope from the opaque token in the
// path, so the frontend sends NO X-Tenant-Id header, NO X-OrderPilot-Permissions header, and
// NO authority query/body fields. The token is the sole credential and travels only in the URL
// path of the fetch — it is never persisted, never logged, and never echoed into analytics.
//
// The response mirrors the backend's redacted public tracking view: customer-safe fields only
// (status label, milestone label/state/evidence, occurred/estimated timestamps, tracking-connected
// flag, generated-at). Every internal identifier, source/actor descriptor, signal, risk, internal
// status, connector descriptor, audit field, and token material is withheld by the backend and
// additionally re-projected away here (see toSafeView).

export type PublicTrackingMilestone = {
  milestoneLabel: string;
  milestoneState: string;
  evidenceLevel: string;
  occurredAt: string | null;
  estimatedAt: string | null;
};

export type PublicOrderTrackingView = {
  statusLabel: string;
  milestones: PublicTrackingMilestone[];
  fulfillmentTrackingConnected: boolean;
  generatedAt: string;
};

// Result states are intentionally coarse so the UI never leaks why a token failed:
// - "invalid": the link is invalid or expired (backend 404). The customer-facing copy is
//   generic; it never distinguishes "wrong token" from "expired" from "revoked".
// - "unavailable": a transient/non-404 failure (network or 5xx). Retry-able messaging.
export type PublicOrderTrackingResult =
  | { ok: true; data: PublicOrderTrackingView }
  | { ok: false; kind: "invalid" | "unavailable" };

// Resolve a public tracking link. The token travels only in the URL path. No auth/tenant/
// permission headers are attached. Non-200 backend bodies are drained and discarded — they are
// never surfaced to the customer (a backend error body could reference internal ids/metadata).
export async function getPublicOrderTracking(token: string): Promise<PublicOrderTrackingResult> {
  if (!token) {
    return { ok: false, kind: "invalid" };
  }
  let response: Response;
  try {
    response = await fetch(
      `${coreApiBaseUrl()}/api/v1/public/order-tracking/${encodeURIComponent(token)}`,
      {
        method: "GET",
        cache: "no-store",
        headers: { "Content-Type": "application/json" }
      }
    );
  } catch {
    return { ok: false, kind: "unavailable" };
  }

  // 404 (and, defensively, 410 Gone) → invalid/expired link. The backend returns a structured
  // NotFoundException for an unknown/expired/revoked token; the customer sees one generic message.
  if (response.status === 404 || response.status === 410) {
    await safeDrain(response);
    return { ok: false, kind: "invalid" };
  }
  if (!response.ok) {
    await safeDrain(response);
    return { ok: false, kind: "unavailable" };
  }

  const text = await safeText(response);
  let parsed: unknown;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    return { ok: false, kind: "unavailable" };
  }
  const view = toSafeView(parsed);
  if (!view) {
    return { ok: false, kind: "unavailable" };
  }
  return { ok: true, data: view };
}

// Re-projects the raw payload onto the customer-safe view, copying ONLY the whitelisted fields.
// Even if a future/misconfigured backend returned extra internal fields, they are dropped here
// and can never reach the rendered page.
function toSafeView(raw: unknown): PublicOrderTrackingView | null {
  if (!raw || typeof raw !== "object") {
    return null;
  }
  const obj = raw as Record<string, unknown>;
  if (typeof obj.statusLabel !== "string" || typeof obj.generatedAt !== "string") {
    return null;
  }
  const milestonesRaw = Array.isArray(obj.milestones) ? obj.milestones : [];
  const milestones: PublicTrackingMilestone[] = milestonesRaw.map((m) => {
    const mo = (m ?? {}) as Record<string, unknown>;
    return {
      milestoneLabel: typeof mo.milestoneLabel === "string" ? mo.milestoneLabel : "",
      milestoneState: typeof mo.milestoneState === "string" ? mo.milestoneState : "UNKNOWN",
      evidenceLevel: typeof mo.evidenceLevel === "string" ? mo.evidenceLevel : "UNKNOWN",
      occurredAt: typeof mo.occurredAt === "string" ? mo.occurredAt : null,
      estimatedAt: typeof mo.estimatedAt === "string" ? mo.estimatedAt : null
    };
  });
  return {
    statusLabel: obj.statusLabel,
    milestones,
    fulfillmentTrackingConnected: obj.fulfillmentTrackingConnected === true,
    generatedAt: obj.generatedAt
  };
}

async function safeText(response: Response): Promise<string> {
  try {
    return await response.text();
  } catch {
    return "";
  }
}

async function safeDrain(response: Response): Promise<void> {
  try {
    await response.text();
  } catch {
    // ignore — body is intentionally discarded
  }
}
