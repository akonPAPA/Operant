import type { NextRequest } from "next/server";
import { NextResponse } from "next/server";

/**
 * Edge Middleware — UX only, never an authority boundary.
 *
 * This module (and its transitive imports) must stay Edge-safe: no bff-proxy,
 * bff-session-store, bff-gateway-signer, node:crypto, redis, or any Node-runtime-only
 * module. It adds security headers, detects only the PRESENCE of the opaque session
 * cookie, and redirects unauthenticated protected-page navigation to /login.
 * Authoritative session validation lives in the Node-runtime route handlers.
 */

const BFF_SESSION_COOKIE = "op_session";

function middlewareBffRuntimeEnabled(): boolean {
  if (process.env.NODE_ENV === "production") {
    return process.env.NEXT_PUBLIC_ORDERPILOT_DEMO_MODE !== "true" && process.env.ORDERPILOT_BFF_ENABLED === "true";
  }
  return process.env.ORDERPILOT_BFF_ENABLED === "true";
}
const PUBLIC_PATH_PREFIXES = ["/login", "/api/auth", "/public", "/api/bff/health"];
const STATIC_PREFIXES = ["/_next", "/favicon.ico"];

function isPublicPath(pathname: string): boolean {
  if (STATIC_PREFIXES.some((p) => pathname.startsWith(p))) {
    return true;
  }
  return PUBLIC_PATH_PREFIXES.some((p) => pathname === p || pathname.startsWith(`${p}/`));
}

/** Cookie presence only — never authentication. */
function hasSessionCookie(request: NextRequest): boolean {
  const value = request.cookies.get(BFF_SESSION_COOKIE)?.value?.trim();
  return Boolean(value && value.length >= 16);
}

export function middleware(request: NextRequest) {
  const response = NextResponse.next();
  response.headers.set("X-Content-Type-Options", "nosniff");
  response.headers.set("X-Frame-Options", "DENY");
  response.headers.set("Referrer-Policy", "no-referrer");
  response.headers.set("Permissions-Policy", "geolocation=()");
  response.headers.set("Cache-Control", "no-store");

  if (!middlewareBffRuntimeEnabled()) {
    return response;
  }

  const { pathname } = request.nextUrl;
  if (isPublicPath(pathname)) {
    return response;
  }

  if (!hasSessionCookie(request)) {
    if (pathname.startsWith("/api/")) {
      return NextResponse.json(
        { message: "Authentication is required." },
        { status: 401, headers: { "Cache-Control": "no-store" } }
      );
    }
    const login = new URL("/login", request.url);
    login.searchParams.set("next", pathname);
    return NextResponse.redirect(login);
  }

  return response;
}

export const config = {
  matcher: ["/((?!_next/static|_next/image).*)"]
};
