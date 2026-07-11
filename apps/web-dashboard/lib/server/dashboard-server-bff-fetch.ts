/**
 * In-process server BFF fetch with explicit cookie header (no Next request globals).
 */
import { BFF_SESSION_COOKIE } from "../bff/bff-config.ts";
import { bffInProcessRequest } from "../bff/bff-in-process-request.ts";
import { readSecurityCookieHeader } from "../bff/bff-cookies.ts";

const SERVER_ALLOWED_METHODS = new Set(["GET", "HEAD"]);
const STRIPPED_INCOMING_HEADERS = new Set([
  "authorization",
  "cookie",
  "x-tenant-id",
  "x-orderpilot-permissions",
  "x-orderpilot-actor-id",
  "x-orderpilot-gateway-signature",
  "x-orderpilot-gateway-timestamp",
  "x-orderpilot-gateway-nonce"
]);

if (typeof window !== "undefined") {
  throw new Error("dashboard-server-bff-fetch cannot be imported in the browser");
}

function sanitizeIncomingHeaders(init?: RequestInit): HeadersInit | undefined {
  if (!init?.headers) {
    return undefined;
  }
  const headers = new Headers(init.headers);
  for (const name of STRIPPED_INCOMING_HEADERS) {
    headers.delete(name);
  }
  return headers;
}

/** Only the `op_session` security cookie is forwarded to the in-process BFF. */
export async function dashboardServerBffFetchWithCookieHeader(
  cookieHeader: string | null | undefined,
  path: string,
  init?: RequestInit
): Promise<Response> {
  const method = (init?.method ?? "GET").toUpperCase();
  if (!SERVER_ALLOWED_METHODS.has(method)) {
    return new Response(JSON.stringify({ error: "SERVER_BFF_MUTATION_DENIED" }), {
      status: 403,
      headers: { "Content-Type": "application/json" }
    });
  }
  const sessionId = readSecurityCookieHeader(cookieHeader, BFF_SESSION_COOKIE);
  const minimalCookie =
    sessionId === undefined
      ? null
      : `${BFF_SESSION_COOKIE}=${encodeURIComponent(sessionId)}`;
  return bffInProcessRequest(minimalCookie, path, {
    ...init,
    method,
    cache: init?.cache ?? "no-store",
    headers: sanitizeIncomingHeaders(init)
  });
}
