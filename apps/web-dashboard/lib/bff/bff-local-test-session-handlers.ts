import "server-only";

import { isLocalTestBootstrapAllowed, isProductionNodeRuntime } from "./bff-deployment-profile.ts";
import { BFF_SESSION_COOKIE } from "./bff-config.ts";
import { readSecurityCookie, type SecurityCookiePolicy } from "./bff-cookies.ts";
import { validateSameOrigin } from "./bff-csrf.ts";
import { replaceOperatorSessionPermissions } from "./bff-session-store.ts";

const SAFE_FAILURE = "Session update is not available.";
const SESSION_COOKIE_POLICY: SecurityCookiePolicy = { minLength: 43, maxLength: 128, pattern: /^[A-Za-z0-9_-]+$/ };
const PERMISSION_VALUE = /^[A-Z][A-Z0-9_]{1,64}$/;
const MAX_PERMISSIONS = 32;
const FORBIDDEN_PREFIXES = ["STAFF_", "SUPPORT_", "ADMIN_", "INTERNAL_"];

function safeJson(status: number, message: string): Response {
  return new Response(JSON.stringify({ message }), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" }
  });
}

/**
 * Local-test E2E only: replace permissions on the active memory session (e.g. revoke QUOTE_ACTION
 * without reloading the page). Never available in production Node runtime.
 */
export async function handleSessionPermissionsPatch(request: Request): Promise<Response> {
  if (isProductionNodeRuntime() || !isLocalTestBootstrapAllowed()) {
    return safeJson(404, SAFE_FAILURE);
  }
  if (!validateSameOrigin(request)) {
    return safeJson(403, SAFE_FAILURE);
  }
  const session = readSecurityCookie(request.headers.get("cookie"), BFF_SESSION_COOKIE, SESSION_COOKIE_POLICY);
  if (session.status !== "valid") {
    return safeJson(401, SAFE_FAILURE);
  }
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return safeJson(400, SAFE_FAILURE);
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    return safeJson(400, SAFE_FAILURE);
  }
  const permissionsRaw = (body as { permissions?: unknown }).permissions;
  if (!Array.isArray(permissionsRaw)) {
    return safeJson(400, SAFE_FAILURE);
  }
  if (permissionsRaw.length === 0 || permissionsRaw.length > MAX_PERMISSIONS) {
    return safeJson(400, SAFE_FAILURE);
  }
  const permissions: string[] = [];
  for (const entry of permissionsRaw) {
    if (typeof entry !== "string" || !PERMISSION_VALUE.test(entry)) {
      return safeJson(400, SAFE_FAILURE);
    }
    if (FORBIDDEN_PREFIXES.some((prefix) => entry.startsWith(prefix))) {
      return safeJson(403, SAFE_FAILURE);
    }
    permissions.push(entry);
  }
  if (new Set(permissions).size !== permissions.length) {
    return safeJson(400, SAFE_FAILURE);
  }
  const updated = await replaceOperatorSessionPermissions(session.value, permissions);
  if (!updated) {
    return safeJson(401, SAFE_FAILURE);
  }
  return new Response(JSON.stringify({ ok: true }), {
    status: 200,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" }
  });
}
