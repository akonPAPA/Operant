/**
 * Node-runtime auth handlers for the BFF session boundary (framework-free for testability).
 *
 * Bootstrap sign-in is a local/test-only bridge until P1-C trusted identity exists:
 * NODE_ENV=production always fails closed before any identity/store/cookie work; local/test
 * bootstrap requires an explicit non-production profile, flags, exact public origin, and bounded
 * server env identity. Request body/query/headers/cookies can never supply tenant, actor, or
 * permissions. Failed authentication sets no cookies and performs no session-store mutation.
 */
import {
  BFF_CSRF_COOKIE,
  BFF_SESSION_COOKIE,
  bffCookieSecure
} from "./bff-config.ts";
import { isLocalTestBootstrapAllowed, isProductionNodeRuntime } from "./bff-deployment-profile.ts";
import { newCsrfToken, sessionMaxAgeSeconds } from "./bff-session.ts";
import {
  persistOperatorSession,
  revokeOperatorSession
} from "./bff-session-store.ts";
import { validateCsrf, validateSameOrigin } from "./bff-csrf.ts";
import { readSecurityCookie, type SecurityCookiePolicy } from "./bff-cookies.ts";

const SAFE_FAILURE = "Sign-in is not available.";
const SAFE_LOGOUT_FAILURE = "Sign-out could not be completed.";
const MAX_BOOTSTRAP_PERMISSIONS = 32;
const UUID_VALUE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const FORBIDDEN_BOOTSTRAP_PERMISSION_PREFIXES = ["STAFF_", "SUPPORT_", "ADMIN_", "INTERNAL_"];
const SESSION_COOKIE_POLICY: SecurityCookiePolicy = { minLength: 43, maxLength: 128, pattern: /^[A-Za-z0-9_-]+$/ };
const CSRF_COOKIE_POLICY: SecurityCookiePolicy = { minLength: 16, maxLength: 256, pattern: /^[A-Za-z0-9_-]+$/ };

function safeJson(status: number, message: string): Response {
  return new Response(JSON.stringify({ message }), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" }
  });
}

/** Bootstrap is unavailable outside the explicit local/test bridge — appear absent. */
function bootstrapUnavailable(): Response {
  return safeJson(404, SAFE_FAILURE);
}

function cookieAttributes(maxAgeSeconds: number): string {
  const secure = bffCookieSecure() ? "; Secure" : "";
  return `Path=/; Max-Age=${maxAgeSeconds}; SameSite=Lax${secure}`;
}

function isForbiddenBootstrapPermission(permission: string): boolean {
  return FORBIDDEN_BOOTSTRAP_PERMISSION_PREFIXES.some((prefix) => permission.startsWith(prefix));
}

function boundedBootstrapIdentity():
  | { tenantId: string; actorId: string; permissions: string[] }
  | null {
  const tenantId = process.env.ORDERPILOT_BFF_BOOTSTRAP_TENANT_ID?.trim();
  const actorId = process.env.ORDERPILOT_BFF_BOOTSTRAP_ACTOR_ID?.trim();
  const rawPermissions = process.env.ORDERPILOT_BFF_BOOTSTRAP_PERMISSIONS?.trim();
  if (!tenantId || !actorId || !rawPermissions || !UUID_VALUE.test(tenantId) || !UUID_VALUE.test(actorId)) {
    return null;
  }
  const raw = rawPermissions.split(",").map((p) => p.trim());
  const permissions = raw.filter((p) => /^[A-Z][A-Z0-9_]{1,64}$/.test(p));
  const unique = new Set(permissions);
  if (
    raw.length !== permissions.length ||
    permissions.length === 0 ||
    permissions.length > MAX_BOOTSTRAP_PERMISSIONS ||
    unique.size !== permissions.length ||
    permissions.some(isForbiddenBootstrapPermission)
  ) {
    return null;
  }
  return { tenantId, actorId, permissions };
}

export async function handleSessionBootstrap(request: Request): Promise<Response> {
  // Production Node runtimes deny before reading identity, session store, CSRF, or cookies.
  if (isProductionNodeRuntime()) {
    return bootstrapUnavailable();
  }
  if (!isLocalTestBootstrapAllowed()) {
    return bootstrapUnavailable();
  }
  // Exact configured public origin required; Host/Forwarded are not authority.
  if (!validateSameOrigin(request)) {
    return safeJson(403, SAFE_FAILURE);
  }
  // The request can carry no identity or authority: any non-empty body is rejected outright,
  // and the query string, headers, and cookies are never read for identity.
  const bodyText = await request.text().catch(() => "");
  if (bodyText.trim().length > 0) {
    return safeJson(400, SAFE_FAILURE);
  }
  const identity = boundedBootstrapIdentity();
  if (!identity) {
    return bootstrapUnavailable();
  }
  const previousSession = readSecurityCookie(request.headers.get("cookie"), BFF_SESSION_COOKIE, SESSION_COOKIE_POLICY);
  if (previousSession.status === "invalid") {
    return safeJson(403, SAFE_FAILURE);
  }
  try {
    // Rotation: any previously issued session ID is invalidated before a new one is minted.
    if (previousSession.status === "valid") {
      await revokeOperatorSession(previousSession.value);
    }
    const { sessionId } = await persistOperatorSession(identity);
    const csrf = newCsrfToken();
    const maxAge = sessionMaxAgeSeconds();
    const headers = new Headers({
      "Content-Type": "application/json",
      "Cache-Control": "no-store"
    });
    headers.append(
      "Set-Cookie",
      `${BFF_SESSION_COOKIE}=${sessionId}; HttpOnly; ${cookieAttributes(maxAge)}`
    );
    headers.append("Set-Cookie", `${BFF_CSRF_COOKIE}=${csrf}; ${cookieAttributes(maxAge)}`);
    return new Response(JSON.stringify({ ok: true }), { status: 200, headers });
  } catch {
    // Session store unavailable (e.g. Redis down): fail closed, set no cookies.
    return safeJson(503, SAFE_FAILURE);
  }
}

export async function handleLogout(request: Request): Promise<Response> {
  // Logout is a browser mutation: same-origin and CSRF are enforced like every other mutation.
  if (!validateSameOrigin(request)) {
    return safeJson(403, SAFE_LOGOUT_FAILURE);
  }
  const cookieHeader = request.headers.get("cookie");
  const csrfCookie = readSecurityCookie(cookieHeader, BFF_CSRF_COOKIE, CSRF_COOKIE_POLICY);
  if (csrfCookie.status !== "valid" || !validateCsrf(request, csrfCookie.value)) {
    return safeJson(403, SAFE_LOGOUT_FAILURE);
  }
  const sessionCookie = readSecurityCookie(cookieHeader, BFF_SESSION_COOKIE, SESSION_COOKIE_POLICY);
  if (sessionCookie.status !== "valid") {
    return safeJson(403, SAFE_LOGOUT_FAILURE);
  }
  try {
    // Revoke the server-side session BEFORE clearing cookies; a Redis failure fails closed.
    await revokeOperatorSession(sessionCookie.value);
  } catch {
    return safeJson(503, SAFE_LOGOUT_FAILURE);
  }
  const headers = new Headers({
    "Content-Type": "application/json",
    "Cache-Control": "no-store"
  });
  headers.append("Set-Cookie", `${BFF_SESSION_COOKIE}=; HttpOnly; ${cookieAttributes(0)}`);
  headers.append("Set-Cookie", `${BFF_CSRF_COOKIE}=; ${cookieAttributes(0)}`);
  return new Response(JSON.stringify({ ok: true }), { status: 200, headers });
}
