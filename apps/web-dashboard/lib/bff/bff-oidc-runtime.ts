import "server-only";

import {
  ClientSecretBasic,
  customFetch,
  discovery,
  type CustomFetch,
  type ServerMetadata
} from "openid-client";

import {
  isValidOidcConfiguration,
  SUPPORTED_OIDC_CLIENT_AUTHENTICATION_METHOD,
  type OidcScope,
  type ValidOidcConfiguration
} from "./bff-oidc-config.ts";
import {
  DEFAULT_OIDC_DISCOVERY_MAX_BODY_BYTES,
  DEFAULT_OIDC_DISCOVERY_TIMEOUT_MS,
  OidcRuntimeNetworkError,
  createBoundedOidcDiscoveryFetch,
  type OidcDiscoveryFetch,
  type OidcRuntimeNetworkErrorCode
} from "./bff-oidc-runtime-network.ts";

const DEFAULT_OIDC_RUNTIME_CACHE_TTL_MS = 5 * 60 * 1000;
const DEFAULT_OIDC_RUNTIME_MAX_CACHE_ENTRIES = 8;
const ALLOWED_ID_TOKEN_SIGNING_ALGORITHMS = ["RS256", "PS256", "ES256"] as const;

type AllowedIdTokenSigningAlgorithm = (typeof ALLOWED_ID_TOKEN_SIGNING_ALGORITHMS)[number];

export type OidcRuntimeErrorCode =
  | "OIDC_CONFIGURATION_INVALID"
  | "OIDC_PROVIDER_ISSUER_MISMATCH"
  | "OIDC_PROVIDER_METADATA_INVALID"
  | "OIDC_PROVIDER_ENDPOINT_UNSAFE"
  | "OIDC_PROVIDER_AUTH_CODE_UNSUPPORTED"
  | "OIDC_PROVIDER_PKCE_S256_UNSUPPORTED"
  | "OIDC_PROVIDER_CLIENT_AUTH_UNSUPPORTED"
  | "OIDC_PROVIDER_ID_TOKEN_ALG_UNSUPPORTED"
  | "OIDC_PROVIDER_DISCOVERY_FAILED"
  | OidcRuntimeNetworkErrorCode;

export type OidcRuntimeError = Readonly<{
  code: OidcRuntimeErrorCode;
  message: string;
}>;

export type OidcProviderRuntime = Readonly<{
  status: "PROVIDER_METADATA_VALIDATED_FOR_FUTURE_AUTHORIZATION_CODE_FLOW";
  issuer: string;
  authorizationEndpoint: string;
  tokenEndpoint: string;
  jwksUri: string;
  supportedScopes: readonly OidcScope[];
  clientAuthenticationMethod: "client_secret_basic";
  idTokenSigningAlgorithms: readonly AllowedIdTokenSigningAlgorithm[];
  validatedAtEpochMs: number;
  expiresAtEpochMs: number;
}>;

export type OidcProviderRuntimeResult =
  | { ok: true; runtime: OidcProviderRuntime }
  | { ok: false; error: OidcRuntimeError };

type OidcRuntimeCacheEntry = {
  runtime: OidcProviderRuntime;
  expiresAtEpochMs: number;
};

export type OidcRuntimeCache = {
  entries: Map<string, OidcRuntimeCacheEntry>;
  inflight: Map<string, Promise<OidcProviderRuntimeResult>>;
};

export type LoadOidcProviderRuntimeOptions = {
  fetch?: OidcDiscoveryFetch;
  now?: () => number;
  timeoutMs?: number;
  maxBodyBytes?: number;
  cacheTtlMs?: number;
  maxCacheEntries?: number;
  cache?: OidcRuntimeCache;
};

const ERROR_MESSAGES: Record<OidcRuntimeErrorCode, string> = {
  OIDC_CONFIGURATION_INVALID: "OIDC configuration is not a validated server configuration.",
  OIDC_PROVIDER_ISSUER_MISMATCH: "OIDC provider issuer did not match configured issuer.",
  OIDC_PROVIDER_METADATA_INVALID: "OIDC provider metadata is incomplete or unsupported.",
  OIDC_PROVIDER_ENDPOINT_UNSAFE: "OIDC provider metadata contains an unsafe endpoint URL.",
  OIDC_PROVIDER_AUTH_CODE_UNSUPPORTED: "OIDC provider does not support authorization code flow.",
  OIDC_PROVIDER_PKCE_S256_UNSUPPORTED: "OIDC provider does not explicitly support PKCE S256.",
  OIDC_PROVIDER_CLIENT_AUTH_UNSUPPORTED: "OIDC provider does not support client_secret_basic.",
  OIDC_PROVIDER_ID_TOKEN_ALG_UNSUPPORTED: "OIDC provider does not publish an allowed ID token signing algorithm.",
  OIDC_PROVIDER_DISCOVERY_FAILED: "OIDC provider discovery failed.",
  OIDC_DISCOVERY_URL_INVALID: "OIDC discovery URL is invalid.",
  OIDC_DISCOVERY_TIMEOUT: "OIDC discovery timed out.",
  OIDC_DISCOVERY_NETWORK_ERROR: "OIDC discovery network request failed.",
  OIDC_DISCOVERY_REDIRECT_REJECTED: "OIDC discovery redirects are not allowed.",
  OIDC_DISCOVERY_HTTP_ERROR: "OIDC discovery returned an unsuccessful HTTP status.",
  OIDC_DISCOVERY_RESPONSE_TOO_LARGE: "OIDC discovery response exceeded the configured size limit.",
  OIDC_DISCOVERY_CONTENT_TYPE_INVALID: "OIDC discovery response must be JSON."
};

const defaultRuntimeCache = createOidcRuntimeCache();

export function createOidcRuntimeCache(): OidcRuntimeCache {
  return { entries: new Map(), inflight: new Map() };
}

function fail(code: OidcRuntimeErrorCode): OidcProviderRuntimeResult {
  return { ok: false, error: Object.freeze({ code, message: ERROR_MESSAGES[code] }) };
}

function denyRuntimeSerialization(runtime: OidcProviderRuntime): OidcProviderRuntime {
  Object.defineProperty(runtime, "toJSON", {
    value() {
      throw new Error("OIDC_PROVIDER_RUNTIME_NOT_PUBLIC");
    },
    enumerable: false
  });
  return runtime;
}

function loopbackOrLocalhost(hostname: string): boolean {
  const host = hostname.toLowerCase();
  return host === "localhost" || host === "127.0.0.1" || host.startsWith("127.") || host === "::1" || host === "[::1]" || host === "::" || host === "[::]" || host === "0.0.0.0";
}

function dynamicMarkerPresent(value: string): boolean {
  const lower = value.toLowerCase();
  return value.includes("*") || value.includes("{") || value.includes("}") || value.includes("$") || lower.includes("%7b") || lower.includes("%7d");
}

function safeHttpsEndpoint(value: unknown): string | null {
  if (typeof value !== "string" || /[\x00-\x1f\x7f]/.test(value) || dynamicMarkerPresent(value)) {
    return null;
  }
  try {
    const parsed = new URL(value);
    if (
      parsed.protocol !== "https:" ||
      parsed.username ||
      parsed.password ||
      parsed.search ||
      parsed.hash ||
      parsed.port === "0" ||
      loopbackOrLocalhost(parsed.hostname)
    ) {
      return null;
    }
    return `${parsed.origin}${parsed.pathname}`;
  } catch {
    return null;
  }
}

function stringArray(value: unknown): string[] | null {
  if (!Array.isArray(value)) {
    return null;
  }
  if (!value.every((entry): entry is string => typeof entry === "string" && entry.length > 0 && entry.length <= 128)) {
    return null;
  }
  return value;
}

function contains(values: unknown, expected: string): boolean {
  return stringArray(values)?.includes(expected) ?? false;
}

function configuredScopesSupported(metadata: ServerMetadata, configuration: ValidOidcConfiguration): boolean {
  const providerScopes = stringArray(metadata.scopes_supported);
  if (!providerScopes) {
    return true;
  }
  return configuration.scopes.every((scope) => providerScopes.includes(scope));
}

function allowedIdTokenAlgorithms(metadata: ServerMetadata): AllowedIdTokenSigningAlgorithm[] {
  const published = stringArray(metadata.id_token_signing_alg_values_supported) ?? [];
  return ALLOWED_ID_TOKEN_SIGNING_ALGORITHMS.filter((algorithm) => published.includes(algorithm));
}

function validateMetadata(
  metadata: ServerMetadata,
  configuration: ValidOidcConfiguration,
  nowEpochMs: number,
  cacheTtlMs: number
): OidcProviderRuntimeResult {
  if (metadata.issuer !== configuration.issuer) {
    return fail("OIDC_PROVIDER_ISSUER_MISMATCH");
  }

  const authorizationEndpoint = safeHttpsEndpoint(metadata.authorization_endpoint);
  const tokenEndpoint = safeHttpsEndpoint(metadata.token_endpoint);
  const jwksUri = safeHttpsEndpoint(metadata.jwks_uri);
  if (!authorizationEndpoint || !tokenEndpoint || !jwksUri) {
    return fail("OIDC_PROVIDER_ENDPOINT_UNSAFE");
  }
  if (!contains(metadata.response_types_supported, "code")) {
    return fail("OIDC_PROVIDER_AUTH_CODE_UNSUPPORTED");
  }
  if (metadata.grant_types_supported !== undefined && !contains(metadata.grant_types_supported, "authorization_code")) {
    return fail("OIDC_PROVIDER_AUTH_CODE_UNSUPPORTED");
  }
  if (!contains(metadata.code_challenge_methods_supported, "S256")) {
    return fail("OIDC_PROVIDER_PKCE_S256_UNSUPPORTED");
  }
  if (!contains(metadata.token_endpoint_auth_methods_supported, "client_secret_basic")) {
    return fail("OIDC_PROVIDER_CLIENT_AUTH_UNSUPPORTED");
  }
  if (!configuredScopesSupported(metadata, configuration)) {
    return fail("OIDC_PROVIDER_METADATA_INVALID");
  }
  const idTokenSigningAlgorithms = allowedIdTokenAlgorithms(metadata);
  if (idTokenSigningAlgorithms.length === 0) {
    return fail("OIDC_PROVIDER_ID_TOKEN_ALG_UNSUPPORTED");
  }

  const runtime = Object.freeze(denyRuntimeSerialization({
    status: "PROVIDER_METADATA_VALIDATED_FOR_FUTURE_AUTHORIZATION_CODE_FLOW" as const,
    issuer: configuration.issuer,
    authorizationEndpoint,
    tokenEndpoint,
    jwksUri,
    supportedScopes: Object.freeze([...configuration.scopes]),
    clientAuthenticationMethod: "client_secret_basic" as const,
    idTokenSigningAlgorithms: Object.freeze(idTokenSigningAlgorithms),
    validatedAtEpochMs: nowEpochMs,
    expiresAtEpochMs: nowEpochMs + cacheTtlMs
  }));
  return { ok: true, runtime };
}

function normalizePositiveInteger(value: number | undefined, fallback: number): number {
  if (value === undefined || !Number.isSafeInteger(value) || value <= 0) {
    return fallback;
  }
  return value;
}

function evictOldestEntries(cache: OidcRuntimeCache, maxCacheEntries: number): void {
  while (cache.entries.size > maxCacheEntries) {
    const oldest = cache.entries.keys().next().value;
    if (typeof oldest !== "string") {
      return;
    }
    cache.entries.delete(oldest);
  }
}

function networkErrorFromCause(error: unknown, depth = 0): OidcRuntimeNetworkError | null {
  if (error instanceof OidcRuntimeNetworkError) {
    return error;
  }
  if (depth >= 4 || !error || typeof error !== "object" || !("cause" in error)) {
    return null;
  }
  return networkErrorFromCause((error as { cause?: unknown }).cause, depth + 1);
}

function mapRuntimeError(error: unknown): OidcProviderRuntimeResult {
  const networkError = networkErrorFromCause(error);
  if (networkError) {
    return fail(networkError.code);
  }
  return fail("OIDC_PROVIDER_DISCOVERY_FAILED");
}

async function discoverProviderRuntime(
  configuration: ValidOidcConfiguration,
  options: LoadOidcProviderRuntimeOptions,
  nowEpochMs: number,
  cacheTtlMs: number,
  cache: OidcRuntimeCache,
  maxCacheEntries: number
): Promise<OidcProviderRuntimeResult> {
  const timeoutMs = normalizePositiveInteger(options.timeoutMs, DEFAULT_OIDC_DISCOVERY_TIMEOUT_MS);
  const boundedFetch = createBoundedOidcDiscoveryFetch({
    fetch: options.fetch,
    timeoutMs,
    maxBodyBytes: normalizePositiveInteger(options.maxBodyBytes, DEFAULT_OIDC_DISCOVERY_MAX_BODY_BYTES)
  }) as CustomFetch;

  try {
    const provider = await discovery(
      new URL(configuration.issuer),
      configuration.clientId,
      {
        client_secret: configuration.clientSecret,
        redirect_uris: [configuration.redirectUri],
        response_types: ["code"],
        token_endpoint_auth_method: "client_secret_basic"
      },
      ClientSecretBasic(configuration.clientSecret),
      {
        [customFetch]: boundedFetch,
        timeout: Math.max(1, Math.ceil(timeoutMs / 1000)),
        algorithm: "oidc"
      }
    );
    const result = validateMetadata(provider.serverMetadata(), configuration, nowEpochMs, cacheTtlMs);
    if (result.ok) {
      cache.entries.set(configuration.issuer, {
        runtime: result.runtime,
        expiresAtEpochMs: result.runtime.expiresAtEpochMs
      });
      evictOldestEntries(cache, maxCacheEntries);
    }
    return result;
  } catch (error) {
    return mapRuntimeError(error);
  }
}

export async function loadOidcProviderRuntime(
  configuration: unknown,
  options: LoadOidcProviderRuntimeOptions = {}
): Promise<OidcProviderRuntimeResult> {
  if (!isValidOidcConfiguration(configuration)) {
    return fail("OIDC_CONFIGURATION_INVALID");
  }

  const nowEpochMs = options.now?.() ?? Date.now();
  const cacheTtlMs = normalizePositiveInteger(options.cacheTtlMs, DEFAULT_OIDC_RUNTIME_CACHE_TTL_MS);
  const maxCacheEntries = normalizePositiveInteger(options.maxCacheEntries, DEFAULT_OIDC_RUNTIME_MAX_CACHE_ENTRIES);
  const cache = options.cache ?? defaultRuntimeCache;
  const cached = cache.entries.get(configuration.issuer);
  if (cached && cached.expiresAtEpochMs > nowEpochMs) {
    return { ok: true, runtime: cached.runtime };
  }
  cache.entries.delete(configuration.issuer);

  const inFlight = cache.inflight.get(configuration.issuer);
  if (inFlight) {
    return inFlight;
  }

  const work = discoverProviderRuntime(configuration, options, nowEpochMs, cacheTtlMs, cache, maxCacheEntries).finally(() => {
    cache.inflight.delete(configuration.issuer);
  });
  cache.inflight.set(configuration.issuer, work);
  return work;
}

export const oidcRuntimeDiscoveryPolicy = Object.freeze({
  discoveryOnly: true,
  loginRoutesImplemented: false,
  callbackRoutesImplemented: false,
  tokenExchangeImplemented: false,
  sessionsImplemented: false,
  tenantMembershipImplemented: false,
  supportedClientAuthenticationMethod: SUPPORTED_OIDC_CLIENT_AUTHENTICATION_METHOD,
  supportedScopes: Object.freeze(["openid", "profile", "email"] as const),
  allowedIdTokenSigningAlgorithms: Object.freeze([...ALLOWED_ID_TOKEN_SIGNING_ALGORITHMS])
});