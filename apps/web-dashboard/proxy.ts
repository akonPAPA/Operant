import type { NextRequest } from "next/server";
import { NextResponse } from "next/server";
import {
  MIDDLEWARE_SECURITY_HEADERS,
  decideEdgeMiddleware
} from "./lib/edge-middleware-core.ts";

/**
 * Edge Middleware — UX only, never an authority boundary.
 *
 * This module (and its transitive imports) must stay Edge-safe: no bff-proxy,
 * bff-session-store, bff-gateway-signer, node:crypto, redis, or any Node-runtime-only
 * module. It adds security headers, detects only the PRESENCE of the opaque session
 * cookie, and redirects unauthenticated protected-page navigation to /login.
 * Authoritative session validation lives in the Node-runtime route handlers.
 *
 * The branch decision and the security-header set live in the pure, unit-tested
 * lib/edge-middleware-core.ts so every response path receives identical headers (F09).
 */

const BFF_SESSION_COOKIE = "op_session";

function middlewareBffRuntimeEnabled(): boolean {
  if (process.env.NODE_ENV === "production") {
    return process.env.NEXT_PUBLIC_ORDERPILOT_DEMO_MODE !== "true" && process.env.ORDERPILOT_BFF_ENABLED === "true";
  }
  return process.env.ORDERPILOT_BFF_ENABLED === "true";
}

/** Cookie presence only — never authentication. */
function hasSessionCookie(request: NextRequest): boolean {
  const value = request.cookies.get(BFF_SESSION_COOKIE)?.value?.trim();
  return Boolean(value && value.length >= 16);
}

function applySecurityHeaders(response: NextResponse): NextResponse {
  for (const [name, value] of Object.entries(MIDDLEWARE_SECURITY_HEADERS)) {
    response.headers.set(name, value);
  }
  return response;
}

export function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const decision = decideEdgeMiddleware({
    pathname,
    search,
    enabled: middlewareBffRuntimeEnabled(),
    hasSessionCookie: hasSessionCookie(request)
  });

  if (decision.kind === "json-401") {
    return applySecurityHeaders(
      NextResponse.json({ message: "Authentication is required." }, { status: 401 })
    );
  }
  if (decision.kind === "redirect") {
    return applySecurityHeaders(NextResponse.redirect(new URL(decision.location, request.url)));
  }
  return applySecurityHeaders(NextResponse.next());
}

export const config = {
  matcher: ["/((?!_next/static|_next/image).*)"]
};
