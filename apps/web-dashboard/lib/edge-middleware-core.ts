/**
 * F09 — pure, Edge-safe middleware core. Framework-free (no next/server) so every decision branch is
 * unit-testable and so the SAME security headers and the SAME safe internal destination are applied
 * on every response path: pass-through, public route, API 401, and login redirect.
 *
 * This module must stay Edge-safe: no Node-runtime imports (only the pure safe-internal-path helper).
 */
import { safeInternalPath } from "./safe-internal-path.ts";

/** Applied to EVERY middleware response, regardless of branch. */
export const MIDDLEWARE_SECURITY_HEADERS: Readonly<Record<string, string>> = Object.freeze({
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
  "Referrer-Policy": "no-referrer",
  "Permissions-Policy": "geolocation=()",
  "Cache-Control": "no-store"
});

const PUBLIC_PATH_PREFIXES = ["/login", "/api/auth", "/public", "/api/bff/health"];
const STATIC_PREFIXES = ["/_next", "/favicon.ico"];

export function isPublicMiddlewarePath(pathname: string): boolean {
  if (STATIC_PREFIXES.some((p) => pathname.startsWith(p))) {
    return true;
  }
  return PUBLIC_PATH_PREFIXES.some((p) => pathname === p || pathname.startsWith(`${p}/`));
}

export type EdgeMiddlewareDecision =
  | { kind: "next" }
  | { kind: "json-401" }
  | { kind: "redirect"; location: string };

/**
 * Decide the middleware outcome from plain, framework-free inputs.
 * - Not enabled / public / has session cookie  -> pass through.
 * - Protected API without session cookie        -> bounded JSON 401 (never an HTML redirect).
 * - Protected page without session cookie        -> redirect to /login with a SAFE internal `next`
 *   destination = validated(pathname + search). The destination is run through safeInternalPath, so
 *   //host, schemes, backslashes, control chars, and (multiply) encoded external redirects collapse
 *   to "/". No fragment or credentials are ever included.
 */
export function decideEdgeMiddleware(input: {
  pathname: string;
  search: string;
  enabled: boolean;
  hasSessionCookie: boolean;
}): EdgeMiddlewareDecision {
  if (!input.enabled || isPublicMiddlewarePath(input.pathname) || input.hasSessionCookie) {
    return { kind: "next" };
  }
  if (input.pathname.startsWith("/api/")) {
    return { kind: "json-401" };
  }
  const safeDestination = safeInternalPath(`${input.pathname}${input.search ?? ""}`);
  return { kind: "redirect", location: `/login?next=${encodeURIComponent(safeDestination)}` };
}
