/**
 * Node-runtime auth handlers for the BFF session boundary (framework-free for testability).
 *
 * Bootstrap sign-in is a local/test-only bridge until P1-C trusted identity exists:
 * production-like deployments always fail closed, identity comes only from bounded server env,
 * and the request body/query/headers/cookies can never supply tenant, actor, or permissions.
 * Failed authentication sets no cookies.
 */
import {
  BFF_CSRF_COOKIE,
  BFF_SESSION_COOKIE,
  bffCookieSecure,
  bffSessionSecret
} from "./bff-config.ts";
import { isLocalTestBootstrapAllowed } from "./bff-deployment-profile.ts";
import { newCsrfToken, sessionMaxAgeSeconds } from "./bff-session.ts";
import {
  persistOperatorSession,
  revokeOperatorSession
} from "./bff-session-store.ts";
import { validateCsrf, validateSameOrigin } from "./bff-csrf.ts";
import { readCookie } from "./bff-cookies.ts";

const SAFE_FAILURE = "Sign-in is not available.";
const SAFE_LOGOUT_FAILURE = "Sign-out could not be completed.";
const MAX_BOOTSTRAP_PERMISSIONS = 32;

function safeJson(status: number, message: string): Response {
  return new Response(JSON.stringify({ message }), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" }
  });
}

function cookieAttributes(maxAgeSeconds: number): string {
  const secure = bffCookieSecure() ? "; Secure" : "";
  return `Path=/; Max-Age=${maxAgeSeconds}; SameSite=Lax${secure}`;
}

function boundedBootstrapIdentity():
  | { tenantId: string; actorId: string; permissions: string[] }
  | null {
  const tenantId = process.env.ORDERPILOT_BFF_BOOTSTRAP_TENANT_ID?.trim();
  const actorId = process.env.ORDERPILOT_BFF_BOOTSTRAP_ACTOR_ID?.trim();
  const rawPermissions = process.env.ORDERPILOT_BFF_BOOTSTRAP_PERMISSIONS?.trim();
  if (!tenantId || !actorId || !rawPermissions) {
    return null;
  }
  const permissions = rawPermissions
    .split(",")
    .map((p) => p.trim())
    .filter((p) => /^[A-Z][A-Z0-9_]{1,64}$/.test(p));
  if (permissions.length === 0 || permissions.length > MAX_BOOTSTRAP_PERMISSIONS) {
    return null;
  }
  return { tenantId, actorId, permissions };
}

export async function handleSessionBootstrap(request: Request): Promise<Response> {
  if (!isLocalTestBootstrapAllowed()) {
    return safeJson(503, SAFE_FAILURE);
  }
  const secret = bffSessionSecret();
  if (!secret || secret.length < 32) {
    return safeJson(503, SAFE_FAILURE);
  }
  // The request can carry no identity or authority: any non-empty body is rejected outright,
  // and the query string, headers, and cookies are never read for identity.
  const bodyText = await request.text().catch(() => "");
  if (bodyText.trim().length > 0) {
    return safeJson(400, SAFE_FAILURE);
  }
  const identity = boundedBootstrapIdentity();
  if (!identity) {
    return safeJson(503, SAFE_FAILURE);
  }
  const previousSessionId = readCookie(request.headers.get("cookie"), BFF_SESSION_COOKIE);
  try {
    // Rotation: any previously issued session ID is invalidated before a new one is minted.
    if (previousSessionId) {
      await revokeOperatorSession(previousSessionId);
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
  if (!validateCsrf(request, readCookie(cookieHeader, BFF_CSRF_COOKIE))) {
    return safeJson(403, SAFE_LOGOUT_FAILURE);
  }
  const sessionId = readCookie(cookieHeader, BFF_SESSION_COOKIE);
  try {
    // Revoke the server-side session BEFORE clearing cookies; a Redis failure fails closed.
    await revokeOperatorSession(sessionId);
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
