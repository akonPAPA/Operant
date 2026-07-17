import "server-only";

import {
  ClientSecretBasic,
  Configuration,
  authorizationCodeGrant,
  buildAuthorizationUrl,
  calculatePKCECodeChallenge,
  customFetch,
  randomNonce,
  randomPKCECodeVerifier,
  randomState
} from "openid-client";
import { validatedOidcClientSecret, type ValidOidcConfiguration } from "./bff-oidc-config.ts";
import type { OidcDiscoveryFetch } from "./bff-oidc-runtime-network.ts";
import type { OidcProviderRuntime } from "./bff-oidc-runtime.ts";
import type { VerifiedOidcPrincipal } from "./bff-oidc-identity-mapping.ts";
import { createOidcTokenRuntimeFetch } from "./bff-oidc-token-transport.ts";

export type OidcLoginTransactionSeed = Readonly<{
  state: string;
  nonce: string;
  pkceVerifier: string;
  pkceChallenge: string;
}>;

export type OidcAuthorizationUrlResult = Readonly<{
  seed: OidcLoginTransactionSeed;
  authorizationUrl: string;
}>;

export type OidcCallbackValidationResult =
  | Readonly<{ ok: true; principal: VerifiedOidcPrincipal }>
  | Readonly<{
      ok: false;
      code: "OIDC_TOKEN_EXCHANGE_FAILED" | "OIDC_ID_TOKEN_MISSING" | "OIDC_ID_TOKEN_CLAIMS_INVALID";
    }>;

const SUBJECT_VALUE = /^[^\x00-\x1f\x7f]{1,256}$/;
const EMAIL_VALUE = /^[^\x00-\x1f\x7f]{3,254}$/;

function createOpenidConfiguration(
  configuration: ValidOidcConfiguration,
  runtime: OidcProviderRuntime,
  baseFetch: OidcDiscoveryFetch,
  transportOptions: { timeoutMs?: number; maxBodyBytes?: number } = {}
): Configuration {
  const clientSecret = validatedOidcClientSecret(configuration);
  if (!clientSecret) throw new TypeError("OIDC_CONFIGURATION_INVALID");
  const oidc = new Configuration(
    {
      issuer: runtime.issuer,
      authorization_endpoint: runtime.authorizationEndpoint,
      token_endpoint: runtime.tokenEndpoint,
      jwks_uri: runtime.jwksUri,
      response_types_supported: ["code"],
      grant_types_supported: ["authorization_code"],
      code_challenge_methods_supported: ["S256"],
      token_endpoint_auth_methods_supported: ["client_secret_basic"],
      id_token_signing_alg_values_supported: [...runtime.idTokenSigningAlgorithms]
    },
    configuration.clientId,
    {
      client_secret: clientSecret,
      redirect_uris: [configuration.redirectUri],
      response_types: ["code"],
      token_endpoint_auth_method: "client_secret_basic",
      id_token_signed_response_alg: runtime.idTokenSigningAlgorithms[0]
    },
    ClientSecretBasic(clientSecret)
  );
  oidc[customFetch] = createOidcTokenRuntimeFetch(runtime, baseFetch, transportOptions);
  return oidc;
}

export async function createOidcAuthorizationUrl(
  configuration: ValidOidcConfiguration,
  runtime: OidcProviderRuntime
): Promise<OidcAuthorizationUrlResult> {
  const state = randomState();
  const nonce = randomNonce();
  const pkceVerifier = randomPKCECodeVerifier();
  const pkceChallenge = await calculatePKCECodeChallenge(pkceVerifier);
  const oidc = createOpenidConfiguration(configuration, runtime, fetch);
  const authorizationUrl = buildAuthorizationUrl(oidc, {
    redirect_uri: configuration.redirectUri,
    scope: configuration.scopes.join(" "),
    state,
    nonce,
    code_challenge: pkceChallenge,
    code_challenge_method: "S256"
  });
  return Object.freeze({
    seed: Object.freeze({ state, nonce, pkceVerifier, pkceChallenge }),
    authorizationUrl: authorizationUrl.href
  });
}

function stringClaim(value: unknown, pattern: RegExp): string | null {
  return typeof value === "string" && pattern.test(value) ? value : null;
}

function audienceMatches(value: unknown, expected: string): boolean {
  return value === expected || (
    Array.isArray(value) &&
    value.every((entry) => typeof entry === "string") &&
    value.includes(expected)
  );
}

export async function exchangeAuthorizationCodeForPrincipal(args: Readonly<{
  configuration: ValidOidcConfiguration;
  runtime: OidcProviderRuntime;
  currentUrl: URL;
  expectedState: string;
  expectedNonce: string;
  pkceVerifier: string;
  fetch: OidcDiscoveryFetch;
  timeoutMs?: number;
  maxBodyBytes?: number;
}>): Promise<OidcCallbackValidationResult> {
  let tokens;
  try {
    tokens = await authorizationCodeGrant(
      createOpenidConfiguration(args.configuration, args.runtime, args.fetch, {
        timeoutMs: args.timeoutMs,
        maxBodyBytes: args.maxBodyBytes
      }),
      args.currentUrl,
      {
        expectedState: args.expectedState,
        expectedNonce: args.expectedNonce,
        pkceCodeVerifier: args.pkceVerifier,
        idTokenExpected: true
      }
    );
  } catch {
    return { ok: false, code: "OIDC_TOKEN_EXCHANGE_FAILED" };
  }

  const claims = tokens.claims();
  if (!claims) return { ok: false, code: "OIDC_ID_TOKEN_MISSING" };
  const issuer = stringClaim(claims.iss, SUBJECT_VALUE);
  const subject = stringClaim(claims.sub, SUBJECT_VALUE);
  if (
    issuer !== args.configuration.issuer ||
    !subject ||
    !audienceMatches(claims.aud, args.configuration.clientId)
  ) {
    return { ok: false, code: "OIDC_ID_TOKEN_CLAIMS_INVALID" };
  }
  if (Array.isArray(claims.aud) && claims.aud.length > 1 && claims.azp !== args.configuration.clientId) {
    return { ok: false, code: "OIDC_ID_TOKEN_CLAIMS_INVALID" };
  }
  if (typeof claims.exp !== "number" || !Number.isSafeInteger(claims.exp)) {
    return { ok: false, code: "OIDC_ID_TOKEN_CLAIMS_INVALID" };
  }
  const email = claims.email === undefined ? undefined : stringClaim(claims.email, EMAIL_VALUE);
  if (claims.email !== undefined && !email) {
    return { ok: false, code: "OIDC_ID_TOKEN_CLAIMS_INVALID" };
  }
  const emailVerified = claims.email_verified === undefined ? undefined : claims.email_verified === true;
  if (claims.email_verified !== undefined && claims.email_verified !== true && claims.email_verified !== false) {
    return { ok: false, code: "OIDC_ID_TOKEN_CLAIMS_INVALID" };
  }

  return Object.freeze({
    ok: true as const,
    principal: Object.freeze({
      issuer,
      subject,
      audience: args.configuration.clientId,
      ...(email ? { email } : {}),
      ...(emailVerified !== undefined ? { emailVerified } : {}),
      claims: Object.freeze({ ...claims }),
      tokenExpiresAtEpochSec: claims.exp
    })
  });
}
