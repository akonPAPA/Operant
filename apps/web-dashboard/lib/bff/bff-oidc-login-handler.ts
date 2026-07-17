import "server-only";

import type { OidcRuntimeCache } from "./bff-oidc-runtime.ts";
import {
  OIDC_LOGIN_BINDING_TTL_SECONDS,
  issueOidcBindingCookie,
  oidcBindingHash
} from "./bff-oidc-browser-binding.ts";

const SAFE_FAILURE = "Sign-in is not available.";

type OidcHandlerOptions = {
  fetch?: (input: string | URL | Request, init?: RequestInit) => Promise<Response>;
  cache?: OidcRuntimeCache;
  now?: () => number;
  timeoutMs?: number;
  maxBodyBytes?: number;
};

function safeFailure(): Response {
  return new Response(JSON.stringify({ message: SAFE_FAILURE }), {
    status: 503,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" }
  });
}

function runtimeFetch(options: OidcHandlerOptions) {
  return options.fetch ?? ((input: string | URL | Request, init?: RequestInit) => fetch(input, init));
}

export async function handleOidcLogin(
  _request: Request,
  options: OidcHandlerOptions = {}
): Promise<Response> {
  const { loadValidatedOidcConfiguration } = await import("./bff-oidc-config.ts");
  const { loadOidcProviderRuntime } = await import("./bff-oidc-runtime.ts");
  const { createOidcAuthorizationUrl } = await import("./bff-oidc-auth-code.ts");
  const {
    newOidcTransactionSecret,
    persistOidcAuthorizationTransaction
  } = await import("./bff-oidc-transaction-store.ts");

  const config = loadValidatedOidcConfiguration();
  if (!config.ok) return safeFailure();
  const runtime = await loadOidcProviderRuntime(config.configuration, {
    ...options,
    fetch: runtimeFetch(options)
  });
  if (!runtime.ok) return safeFailure();

  try {
    const auth = await createOidcAuthorizationUrl(config.configuration, runtime.runtime);
    const browserBinding = newOidcTransactionSecret();
    const nowEpochSec = Math.floor((options.now?.() ?? Date.now()) / 1000);
    await persistOidcAuthorizationTransaction({
      state: auth.seed.state,
      nonce: auth.seed.nonce,
      pkceVerifier: auth.seed.pkceVerifier,
      browserBindingHash: oidcBindingHash(browserBinding),
      redirectUri: config.configuration.redirectUri,
      issuer: config.configuration.issuer,
      audience: config.configuration.clientId,
      createdAtEpochSec: nowEpochSec,
      expiresAtEpochSec: nowEpochSec + OIDC_LOGIN_BINDING_TTL_SECONDS
    });
    const headers = new Headers({ Location: auth.authorizationUrl, "Cache-Control": "no-store" });
    issueOidcBindingCookie(headers, browserBinding);
    return new Response(null, { status: 302, headers });
  } catch {
    return safeFailure();
  }
}
