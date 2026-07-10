import type { NextRequest } from "next/server";
import { NextResponse } from "next/server";
import { BFF_SESSION_COOKIE, bffRuntimeMode } from "@/lib/bff/bff-config";

/**
 * Edge Middleware — UX only, never an authority boundary.
 *
 * This module (and its transitive imports) must stay Edge-safe: no bff-proxy,
 * bff-session-store, bff-gateway-signer, node:crypto, redis, or any Node-runtime-only
 * module. It adds security headers, detects only the PRESENCE of the opaque session
 * cookie, and redirects unauthenticated protected-page navigation to /login.
 * Authoritative session validation lives in the Node-runtime route handlers.
 */

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

  if (bffRuntimeMode() !== "bff-production") {
    return response;
  }

  const { pathname } = request.nextUrl;
  if (isPublicPath(pathname)) {
    return response;
  }

  if (!hasSessionCookie(request)) {
    const login = new URL("/login", request.url);
    login.searchParams.set("next", pathname);
    return NextResponse.redirect(login);
  }

  return response;
}

export const config = {
  matcher: ["/((?!_next/static|_next/image).*)"]
};
