import "server-only";

import { BFF_SESSION_COOKIE } from "./bff-config.ts";
import { newCsrfToken, sessionMaxAgeSeconds } from "./bff-session.ts";
import { persistOperatorSession, revokeOperatorSession } from "./bff-session-store.ts";
import { readSecurityCookie, type SecurityCookiePolicy } from "./bff-cookies.ts";
import type { OidcRuntimeCache } from "./bff-oidc-runtime.ts";
import {
  clearOidcBindingCookie,
  oidcBindingHash,
  readOidcBindingCookie,
  sameOidcBindingHash
} from "./bff-oidc-browser-binding.ts";
import { issueAuthCookies } from "./bff-auth-cookie-writer.ts";

const SESSION_COOKIE_POLICY: SecurityCookiePolicy = {
  minLength: 43,
  maxLength: 128,
  pattern: /^[A-Za-z0-9_-]+$/
};

type OidcHandlerOptions = {
  fetch?: (input: string | URL | Request, init?: RequestInit) => Promise<Response>;
  cache?: OidcRuntimeCache;
  now?: () => number;
  timeoutMs?: number;
  maxBodyBytes?: number;
};

function runtimeFetch(options: OidcHandlerOptions) {
  return options.fetch ?? ((input: string | URL | Request, init?: RequestInit) => fetch(input, init));
}

function authFailure(): Response {
  const headers = new Headers({ Location: "/login?auth=failed", "Cache-Control": "no-store" });
  clearOidcBindingCookie(headers);
  return new Response(null, { status: 302, headers });
}

function mappedPermissions(value: unknown): string[] | null {
  return Array.isArray(value) && value.length > 0 && value.every((entry) => typeof entry === "string")
    ? [...value]
    : null;
}

export async function handleOidcCallback(
  request: Request,
  options: OidcHandlerOptions = {}
): Promise<Response> {
  const { loadValidatedOidcConfiguration } = await import("./bff-oidc-config.ts");
  const { loadOidcProviderRuntime } = await import("./bff-oidc-runtime.ts");
  const { exchangeAuthorizationCodeForPrincipal } = await import("./bff-oidc-auth-code.ts");
  const { consumeOidcAuthorizationTransaction } = await import("./bff-oidc-transaction-store.ts");
  const { getProductionOidcIdentityMappingResolver } = await import("./bff-oidc-identity-mapping.ts");

  let callbackUrl: URL;
  try {
    callbackUrl = new URL(request.url);
  } catch {
    return authFailure();
  }
  const code = callbackUrl.searchParams.get("code");
  const state = callbackUrl.searchParams.get("state");
  if (!code || code.length > 4096 || !state || state.length > 256 || callbackUrl.searchParams.has("error")) {
    return authFailure();
  }

  const bindingCookie = readOidcBindingCookie(request);
  if (!bindingCookie) return authFailure();
  const transaction = await consumeOidcAuthorizationTransaction(state);
  if (!transaction) return authFailure();
  if (!sameOidcBindingHash(oidcBindingHash(bindingCookie), transaction.browserBindingHash)) {
    return authFailure();
  }

  const config = loadValidatedOidcConfiguration();
  if (
    !config.ok ||
    config.configuration.redirectUri !== transaction.redirectUri ||
    config.configuration.issuer !== transaction.issuer ||
    config.configuration.clientId !== transaction.audience
  ) {
    return authFailure();
  }
  const runtime = await loadOidcProviderRuntime(config.configuration, {
    ...options,
    fetch: runtimeFetch(options)
  });
  if (!runtime.ok) return authFailure();

  const currentUrl = new URL(config.configuration.redirectUri);
  currentUrl.search = callbackUrl.search;
  const principalResult = await exchangeAuthorizationCodeForPrincipal({
    configuration: config.configuration,
    runtime: runtime.runtime,
    currentUrl,
    expectedState: transaction.state,
    expectedNonce: transaction.nonce,
    pkceVerifier: transaction.pkceVerifier,
    fetch: runtimeFetch(options),
    timeoutMs: options.timeoutMs,
    maxBodyBytes: options.maxBodyBytes
  });
  if (!principalResult.ok) return authFailure();

  const mapped = getProductionOidcIdentityMappingResolver()(principalResult.principal);
  const permissions = mapped.status === "MAPPED"
    ? mappedPermissions(mapped.safeProjection.permissions)
    : null;
  if (
    mapped.status !== "MAPPED" ||
    mapped.accessPlane !== "TENANT_USER" ||
    !mapped.tenantRef ||
    !mapped.actorRef ||
    !permissions
  ) {
    return authFailure();
  }

  const previousSession = readSecurityCookie(
    request.headers.get("cookie"),
    BFF_SESSION_COOKIE,
    SESSION_COOKIE_POLICY
  );
  if (previousSession.status === "invalid") return authFailure();

  try {
    if (previousSession.status === "valid") await revokeOperatorSession(previousSession.value);
    const { sessionId } = await persistOperatorSession({
      tenantId: mapped.tenantRef,
      actorId: mapped.actorRef,
      permissions
    });
    const headers = new Headers({ Location: "/", "Cache-Control": "no-store" });
    clearOidcBindingCookie(headers);
    issueAuthCookies(headers, sessionId, newCsrfToken(), sessionMaxAgeSeconds());
    return new Response(null, { status: 302, headers });
  } catch {
    return authFailure();
  }
}
