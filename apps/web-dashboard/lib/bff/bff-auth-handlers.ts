import {
  BFF_CSRF_COOKIE,
  BFF_SESSION_COOKIE
} from "./bff-config.ts";
import { isLocalTestBootstrapAllowed, isProductionNodeRuntime } from "./bff-deployment-profile.ts";
import { newCsrfToken, sessionMaxAgeSeconds } from "./bff-session.ts";
import { persistOperatorSession, revokeOperatorSession } from "./bff-session-store.ts";
import { validateCsrf, validateSameOrigin } from "./bff-csrf.ts";
import { readSecurityCookie, type SecurityCookiePolicy } from "./bff-cookies.ts";
import { clearAuthCookies, issueAuthCookies } from "./bff-auth-cookie-writer.ts";
import { clearOidcBindingCookie } from "./bff-oidc-browser-binding.ts";

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

function unavailable(): Response {
  return safeJson(404, SAFE_FAILURE);
}

function boundedBootstrapIdentity(): { tenantId: string; actorId: string; permissions: string[] } | null {
  const tenantId = process.env.ORDERPILOT_BFF_BOOTSTRAP_TENANT_ID?.trim();
  const actorId = process.env.ORDERPILOT_BFF_BOOTSTRAP_ACTOR_ID?.trim();
  const rawPermissions = process.env.ORDERPILOT_BFF_BOOTSTRAP_PERMISSIONS?.trim();
  if (!tenantId || !actorId || !rawPermissions || !UUID_VALUE.test(tenantId) || !UUID_VALUE.test(actorId)) return null;
  const raw = rawPermissions.split(",").map((permission) => permission.trim());
  const permissions = raw.filter((permission) => /^[A-Z][A-Z0-9_]{1,64}$/.test(permission));
  if (
    raw.length !== permissions.length || permissions.length === 0 ||
    permissions.length > MAX_BOOTSTRAP_PERMISSIONS || new Set(permissions).size !== permissions.length ||
    permissions.some((permission) => FORBIDDEN_BOOTSTRAP_PERMISSION_PREFIXES.some((prefix) => permission.startsWith(prefix)))
  ) return null;
  return { tenantId, actorId, permissions };
}

export async function handleSessionBootstrap(request: Request): Promise<Response> {
  if (isProductionNodeRuntime() || !isLocalTestBootstrapAllowed()) return unavailable();
  if (!validateSameOrigin(request)) return safeJson(403, SAFE_FAILURE);
  const body = await request.text().catch(() => "");
  if (body.trim()) return safeJson(400, SAFE_FAILURE);
  const identity = boundedBootstrapIdentity();
  if (!identity) return unavailable();
  const previous = readSecurityCookie(request.headers.get("cookie"), BFF_SESSION_COOKIE, SESSION_COOKIE_POLICY);
  if (previous.status === "invalid") return safeJson(403, SAFE_FAILURE);
  try {
    if (previous.status === "valid") await revokeOperatorSession(previous.value);
    const { sessionId } = await persistOperatorSession(identity);
    const headers = new Headers({ "Content-Type": "application/json", "Cache-Control": "no-store" });
    issueAuthCookies(headers, sessionId, newCsrfToken(), sessionMaxAgeSeconds());
    return new Response(JSON.stringify({ ok: true }), { status: 200, headers });
  } catch {
    return safeJson(503, SAFE_FAILURE);
  }
}

export async function handleOidcLogin(request: Request, options: unknown = {}): Promise<Response> {
  const oidcLoginModule = await import("./bff-oidc-login-handler.ts");
  return oidcLoginModule.handleOidcLogin(request, options as never);
}

export async function handleOidcCallback(request: Request, options: unknown = {}): Promise<Response> {
  const oidcCallbackModule = await import("./bff-oidc-callback-handler.ts");
  return oidcCallbackModule.handleOidcCallback(request, options as never);
}

export async function handleLogout(request: Request): Promise<Response> {
  if (!validateSameOrigin(request)) return safeJson(403, SAFE_LOGOUT_FAILURE);
  const cookieHeader = request.headers.get("cookie");
  const session = readSecurityCookie(cookieHeader, BFF_SESSION_COOKIE, SESSION_COOKIE_POLICY);
  if (session.status === "invalid") return safeJson(403, SAFE_LOGOUT_FAILURE);
  const headers = new Headers({ "Content-Type": "application/json", "Cache-Control": "no-store" });
  if (session.status === "missing") {
    clearAuthCookies(headers);
    clearOidcBindingCookie(headers);
    return new Response(JSON.stringify({ ok: true }), { status: 200, headers });
  }
  const csrf = readSecurityCookie(cookieHeader, BFF_CSRF_COOKIE, CSRF_COOKIE_POLICY);
  if (csrf.status !== "valid" || !validateCsrf(request, csrf.value)) return safeJson(403, SAFE_LOGOUT_FAILURE);
  try {
    await revokeOperatorSession(session.value);
  } catch {
    return safeJson(503, SAFE_LOGOUT_FAILURE);
  }
  clearAuthCookies(headers);
  clearOidcBindingCookie(headers);
  return new Response(JSON.stringify({ ok: true }), { status: 200, headers });
}
