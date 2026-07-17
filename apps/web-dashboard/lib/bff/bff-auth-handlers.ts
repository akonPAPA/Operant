/**
 * Node-runtime auth handlers for the BFF session boundary (framework-free for testability).
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
import type { OidcRuntimeCache } from "./bff-oidc-runtime.ts";

const SAFE_FAILURE = "Sign-in is not available.";
const SAFE_LOGOUT_FAILURE = "Sign-out could not be completed.";
const MAX_BOOTSTRAP_PERMISSIONS = 32;
const UUID_VALUE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const FORBIDDEN_BOOTSTRAP_PERMISSION_PREFIXES = ["STAFF_", "SUPPORT_", "ADMIN_", "INTERNAL_"];
const SESSION_COOKIE_POLICY: SecurityCookiePolicy = { minLength: 43, maxLength: 128, pattern: /^[A-Za-z0-9_-]+$/ };
const CSRF_COOKIE_POLICY: SecurityCookiePolicy = { minLength: 16, maxLength: 256, pattern: /^[A-Za-z0-9_-]+$/ };
const OIDC_TRANSACTION_TTL_SECONDS = 300;

type OidcHandlerOptions = { fetch?: (input: string | URL | Request, init?: RequestInit) => Promise<Response>; cache?: OidcRuntimeCache; now?: () => number; timeoutMs?: number; maxBodyBytes?: number; };

function safeJson(status: number, message: string): Response {
  return new Response(JSON.stringify({ message }), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" }
  });
}

function bootstrapUnavailable(): Response {
  return safeJson(404, SAFE_FAILURE);
}

function cookieAttributes(maxAgeSeconds: number): string {
  const secure = bffCookieSecure() ? "; Secure" : "";
  return `Path=/; Max-Age=${maxAgeSeconds}; SameSite=Lax${secure}`;
}

function clearAuthCookies(headers: Headers): void {
  headers.append("Set-Cookie", `${BFF_SESSION_COOKIE}=; HttpOnly; ${cookieAttributes(0)}`);
  headers.append("Set-Cookie", `${BFF_CSRF_COOKIE}=; ${cookieAttributes(0)}`);
}

function issueAuthCookies(headers: Headers, sessionId: string, csrf: string, maxAge: number): void {
  headers.append("Set-Cookie", `${BFF_SESSION_COOKIE}=${sessionId}; HttpOnly; ${cookieAttributes(maxAge)}`);
  headers.append("Set-Cookie", `${BFF_CSRF_COOKIE}=${csrf}; ${cookieAttributes(maxAge)}`);
}

function isForbiddenBootstrapPermission(permission: string): boolean {
  return FORBIDDEN_BOOTSTRAP_PERMISSION_PREFIXES.some((prefix) => permission.startsWith(prefix));
}

function boundedBootstrapIdentity(): { tenantId: string; actorId: string; permissions: string[] } | null {
  const tenantId = process.env.ORDERPILOT_BFF_BOOTSTRAP_TENANT_ID?.trim();
  const actorId = process.env.ORDERPILOT_BFF_BOOTSTRAP_ACTOR_ID?.trim();
  const rawPermissions = process.env.ORDERPILOT_BFF_BOOTSTRAP_PERMISSIONS?.trim();
  if (!tenantId || !actorId || !rawPermissions || !UUID_VALUE.test(tenantId) || !UUID_VALUE.test(actorId)) return null;
  const raw = rawPermissions.split(",").map((p) => p.trim());
  const permissions = raw.filter((p) => /^[A-Z][A-Z0-9_]{1,64}$/.test(p));
  const unique = new Set(permissions);
  if (raw.length !== permissions.length || permissions.length === 0 || permissions.length > MAX_BOOTSTRAP_PERMISSIONS || unique.size !== permissions.length || permissions.some(isForbiddenBootstrapPermission)) return null;
  return { tenantId, actorId, permissions };
}

export async function handleSessionBootstrap(request: Request): Promise<Response> {
  if (isProductionNodeRuntime()) return bootstrapUnavailable();
  if (!isLocalTestBootstrapAllowed()) return bootstrapUnavailable();
  if (!validateSameOrigin(request)) return safeJson(403, SAFE_FAILURE);
  const bodyText = await request.text().catch(() => "");
  if (bodyText.trim().length > 0) return safeJson(400, SAFE_FAILURE);
  const identity = boundedBootstrapIdentity();
  if (!identity) return bootstrapUnavailable();
  const previousSession = readSecurityCookie(request.headers.get("cookie"), BFF_SESSION_COOKIE, SESSION_COOKIE_POLICY);
  if (previousSession.status === "invalid") return safeJson(403, SAFE_FAILURE);
  try {
    if (previousSession.status === "valid") await revokeOperatorSession(previousSession.value);
    const { sessionId } = await persistOperatorSession(identity);
    const csrf = newCsrfToken();
    const headers = new Headers({ "Content-Type": "application/json", "Cache-Control": "no-store" });
    issueAuthCookies(headers, sessionId, csrf, sessionMaxAgeSeconds());
    return new Response(JSON.stringify({ ok: true }), { status: 200, headers });
  } catch {
    return safeJson(503, SAFE_FAILURE);
  }
}

function runtimeFetch(options: OidcHandlerOptions): (input: string | URL | Request, init?: RequestInit) => Promise<Response> {
  return options.fetch ?? ((input, init) => fetch(input, init));
}

export async function handleOidcLogin(_request: Request, options: OidcHandlerOptions = {}): Promise<Response> {
  const { loadValidatedOidcConfiguration } = await import("./bff-oidc-config.ts");
  const { loadOidcProviderRuntime } = await import("./bff-oidc-runtime.ts");
  const { createOidcAuthorizationUrl } = await import("./bff-oidc-auth-code.ts");
  const { persistOidcAuthorizationTransaction } = await import("./bff-oidc-transaction-store.ts");
  const config = loadValidatedOidcConfiguration();
  if (!config.ok) return safeJson(503, SAFE_FAILURE);
  const runtime = await loadOidcProviderRuntime(config.configuration, { ...options, fetch: runtimeFetch(options) });
  if (!runtime.ok) return safeJson(503, SAFE_FAILURE);
  try {
    const auth = await createOidcAuthorizationUrl(config.configuration, runtime.runtime);
    const now = Math.floor(Date.now() / 1000);
    await persistOidcAuthorizationTransaction({
      state: auth.seed.state,
      nonce: auth.seed.nonce,
      pkceVerifier: auth.seed.pkceVerifier,
      redirectUri: config.configuration.redirectUri,
      issuer: config.configuration.issuer,
      audience: config.configuration.clientId,
      createdAtEpochSec: now,
      expiresAtEpochSec: now + OIDC_TRANSACTION_TTL_SECONDS
    });
    return new Response(null, { status: 302, headers: { Location: auth.authorizationUrl, "Cache-Control": "no-store" } });
  } catch {
    return safeJson(503, SAFE_FAILURE);
  }
}

function safeAuthFailureRedirect(): Response {
  return new Response(null, { status: 302, headers: { Location: "/login?auth=failed", "Cache-Control": "no-store" } });
}

export async function handleOidcCallback(request: Request, options: OidcHandlerOptions = {}): Promise<Response> {
  const { loadValidatedOidcConfiguration } = await import("./bff-oidc-config.ts");
  const { loadOidcProviderRuntime } = await import("./bff-oidc-runtime.ts");
  const { exchangeAuthorizationCodeForPrincipal } = await import("./bff-oidc-auth-code.ts");
  const { consumeOidcAuthorizationTransaction } = await import("./bff-oidc-transaction-store.ts");
  const { getProductionOidcIdentityMappingResolver } = await import("./bff-oidc-identity-mapping.ts");
  let callbackUrl: URL;
  try { callbackUrl = new URL(request.url); } catch { return safeAuthFailureRedirect(); }
  const code = callbackUrl.searchParams.get("code");
  const state = callbackUrl.searchParams.get("state");
  if (!code || !state || callbackUrl.searchParams.has("error")) return safeAuthFailureRedirect();
  const transaction = await consumeOidcAuthorizationTransaction(state);
  if (!transaction) return safeAuthFailureRedirect();
  const config = loadValidatedOidcConfiguration();
  if (!config.ok || config.configuration.redirectUri !== transaction.redirectUri || config.configuration.issuer !== transaction.issuer || config.configuration.clientId !== transaction.audience) {
    return safeAuthFailureRedirect();
  }
  const runtime = await loadOidcProviderRuntime(config.configuration, { ...options, fetch: runtimeFetch(options) });
  if (!runtime.ok) return safeAuthFailureRedirect();
  const currentUrl = new URL(config.configuration.redirectUri);
  currentUrl.search = callbackUrl.search;
  const principalResult = await exchangeAuthorizationCodeForPrincipal({
    configuration: config.configuration,
    runtime: runtime.runtime,
    currentUrl,
    expectedState: transaction.state,
    expectedNonce: transaction.nonce,
    pkceVerifier: transaction.pkceVerifier,
    fetch: runtimeFetch(options)
  });
  if (!principalResult.ok) return safeAuthFailureRedirect();
  const mapped = getProductionOidcIdentityMappingResolver()(principalResult.principal);
  if (mapped.status !== "MAPPED" || mapped.accessPlane !== "TENANT_USER" || !mapped.tenantRef || !mapped.actorRef || !Array.isArray(mapped.safeProjection.permissions)) {
    return safeAuthFailureRedirect();
  }
  const previousSession = readSecurityCookie(request.headers.get("cookie"), BFF_SESSION_COOKIE, SESSION_COOKIE_POLICY);
  if (previousSession.status === "invalid") return safeAuthFailureRedirect();
  try {
    if (previousSession.status === "valid") await revokeOperatorSession(previousSession.value);
    const { sessionId } = await persistOperatorSession({
      tenantId: mapped.tenantRef,
      actorId: mapped.actorRef,
      permissions: mapped.safeProjection.permissions as string[]
    });
    const headers = new Headers({ "Cache-Control": "no-store", Location: "/" });
    issueAuthCookies(headers, sessionId, newCsrfToken(), sessionMaxAgeSeconds());
    return new Response(null, { status: 302, headers });
  } catch {
    return safeAuthFailureRedirect();
  }
}

export async function handleLogout(request: Request): Promise<Response> {
  if (!validateSameOrigin(request)) return safeJson(403, SAFE_LOGOUT_FAILURE);
  const cookieHeader = request.headers.get("cookie");
  const sessionCookie = readSecurityCookie(cookieHeader, BFF_SESSION_COOKIE, SESSION_COOKIE_POLICY);
  if (sessionCookie.status === "invalid") return safeJson(403, SAFE_LOGOUT_FAILURE);
  const headers = new Headers({ "Content-Type": "application/json", "Cache-Control": "no-store" });
  if (sessionCookie.status === "missing") {
    clearAuthCookies(headers);
    return new Response(JSON.stringify({ ok: true }), { status: 200, headers });
  }
  const csrfCookie = readSecurityCookie(cookieHeader, BFF_CSRF_COOKIE, CSRF_COOKIE_POLICY);
  if (csrfCookie.status !== "valid" || !validateCsrf(request, csrfCookie.value)) return safeJson(403, SAFE_LOGOUT_FAILURE);
  try {
    await revokeOperatorSession(sessionCookie.value);
  } catch {
    return safeJson(503, SAFE_LOGOUT_FAILURE);
  }
  clearAuthCookies(headers);
  return new Response(JSON.stringify({ ok: true }), { status: 200, headers });
}