import { handleUiCapabilityProjection } from "@/lib/bff/bff-ui-capability-handlers";

/**
 * Same-origin UI capability projection for the tenant shell.
 * Offer filtering only — Core authorizes every route and mutation.
 *
 * Tenant User Access only. Never cache across users.
 */
export const dynamic = "force-dynamic";
export const runtime = "nodejs";

export async function GET(request: Request): Promise<Response> {
  return handleUiCapabilityProjection(request);
}

export async function HEAD(request: Request): Promise<Response> {
  return handleUiCapabilityProjection(request);
}
