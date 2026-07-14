// OP-CAP-46H — Operator tracking-link registry client (list + revoke).
//
// This lives in its own module (separate from `order-journey-api.ts`) so the operator registry's
// `linkId` handle never bleeds into the one-time create/mapping surface, whose contract forbids any
// link identifier. Reads require ANALYTICS_READ; revoke requires REVIEW_ACTION — both re-validated
// by the backend ApiPermissionInterceptor. Tenant scope rides ONLY in the `X-Tenant-Id` header; no
// request body ever carries tenant/actor/token authority.
//
// The list response carries only safe lifecycle metadata (linkId, timestamps, derived status) — never
// the raw token, its hash, the public tracking path, or the customer URL. Nothing here is ever written
// to localStorage / sessionStorage, logged, or sent to analytics.

import { dashboardRequestHeaders, enrichDashboardRequestInit, isDashboardApiAuthorityAvailable } from "./api-transport";
import { orderJourneyClient } from "@/lib/order-journey-api";

const ANALYTICS_READ = "ANALYTICS_READ";
const REVIEW_ACTION = "REVIEW_ACTION";

// Backend-derived lifecycle status. The frontend never invents a status — it renders exactly what the
// backend reports.
export type TrackingLinkStatus = "ACTIVE" | "EXPIRED" | "REVOKED";

// One safe operator registry row. Mirrors the backend TrackingLinkSummaryDto: the internal `linkId`
// (the handle used to revoke — NEVER the raw token or its hash), lifecycle timestamps, and status.
// No token, tokenHash, trackingPath, customer URL, tenantId, revocation reason, or actor id.
export type TrackingLinkSummary = {
  linkId: string;
  createdAt: string;
  expiresAt: string;
  revokedAt: string | null;
  status: TrackingLinkStatus;
};

export type TrackingLinkList = { links: TrackingLinkSummary[] };

export type TrackingLinkListResult = { data: TrackingLinkList | null; error?: string };

// List a journey's secure tracking links (newest first). GET-only, tenant-scoped via header. Non-2xx
// and unreachable responses become a safe `error` string; the raw backend body is never surfaced.
export async function listOrderJourneyTrackingLinks(journeyId: string): Promise<TrackingLinkListResult> {
  if (!isDashboardApiAuthorityAvailable(orderJourneyClient.tenantId)) {
    return { data: null, error: "Authenticated dashboard access is unavailable." };
  }
  let response: Response;
  try {
    response = await fetch(
      `${orderJourneyClient.baseUrl}/api/v1/order-journeys/${encodeURIComponent(journeyId)}/tracking-links`,
      {
        method: "GET",
        cache: "no-store",
        headers: dashboardRequestHeaders(orderJourneyClient.tenantId, ANALYTICS_READ)
      }
    );
  } catch {
    return { data: null, error: "Core API not reachable." };
  }
  const text = await response.text();
  if (!response.ok) {
    return { data: null, error: `Core API returned ${response.status}.` };
  }
  const parsed = text ? (JSON.parse(text) as Partial<TrackingLinkList>) : null;
  return { data: { links: Array.isArray(parsed?.links) ? parsed.links : [] } };
}

// Revoke a single tracking link by its listed `linkId`. POST-only, REVIEW_ACTION, tenant via header.
// The request body is empty (business intent only — no reason field in this slice) and NEVER carries
// tenant/actor/token/state authority; the link is identified solely by the path. Non-2xx responses are
// thrown with the HTTP `status` attached so the caller can map them through `mapOperatorActionError`;
// the raw backend body is drained and never surfaced (it may carry internal ids).
export async function revokeOrderJourneyTrackingLink(journeyId: string, linkId: string): Promise<void> {
  if (!isDashboardApiAuthorityAvailable(orderJourneyClient.tenantId)) {
    throw Object.assign(new Error("Tenant scope is not configured."), { status: 0 });
  }
  let response: Response;
  try {
    response = await fetch(
      `${orderJourneyClient.baseUrl}/api/v1/order-journeys/${encodeURIComponent(journeyId)}` +
        `/tracking-links/${encodeURIComponent(linkId)}/revoke`,
      enrichDashboardRequestInit({
        method: "POST",
        cache: "no-store",
        headers: {
          "Content-Type": "application/json",
          "X-OrderPilot-Permissions": REVIEW_ACTION,
          "X-Tenant-Id": orderJourneyClient.tenantId
        },
        body: JSON.stringify({})
      })
    );
  } catch {
    throw Object.assign(new Error("Core API is not reachable."), { status: 0 });
  }
  if (!response.ok) {
    try {
      await response.text();
    } catch {
      // ignore — never surface the raw backend body
    }
    throw Object.assign(new Error(`Core API returned ${response.status}.`), { status: response.status });
  }
  // Drain the success body; the operator UI re-fetches the list rather than trusting an echoed row.
  try {
    await response.text();
  } catch {
    // ignore
  }
}

// OP-CAP-46H — the event the create affordance dispatches after a successful mint so the registry can
// refresh. It carries ONLY the journeyId — never the token, path, or any link metadata.
export const TRACKING_LINK_CREATED_EVENT = "orderpilot:tracking-link-created";
