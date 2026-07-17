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
  validatedOidcClientSecret,
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
const MAX_OIDC_RUNTIME_CACHE_TTL_MS = 15 * 60 * 1000;
const DEFAULT_OIDC_RUNTIME_MAX_CACHE_ENTRIES = 8;
const MAX_OIDC_RUNTIME_CACHE_ENTRIES = 64;
const ALLOWED_ID_TOKEN_SIGNING_ALGORITHMS = ["RS256", "PS256", "ES256"] as const;

type AllowedIdTokenSigningAlgorithm = (typeof ALLOWED_ID_TOKEN_SIGNING_ALGORITHMS)[number];

type CachedProviderMetadata = Readonly<{
  issuer?: unknown;
  authorization_endpoint?: unknown;
  token_endpoint?: unknown;
  jwks_uri?: unknown;
  response_types_supported?: readonly unknown[];
  grant_types_supported?: readonly unknown[];
  code_challenge_methods_supported?: readonly unknown[];
  token_endpoint_auth_methods_supported?: readonly unknown[];
  scopes_supported?: readonly unknown[];
  id_token_signing_alg_values_supported?: readonly unknown[];
}>;

type OidcMetadataFetchResult =
  | { ok: true; metadata: CachedProviderMetadata; fetchedAtEpochMs: number }
  | { ok: false; error: OidcRuntimeError };

export type OidcRuntimeErrorCode =
  | "OIDC_CONFIGURATION_INVALID"
  | "OIDC_CONTROLLED_EGRESS_REQUIRED"
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
  metadata: CachedProviderMetadata;
  expiresAtEpochMs: number;
};

export type OidcRuntimeCache = {
  entries: Map<string, OidcRuntimeCacheEntry>;
  inflight: Map<string, Promise<OidcMetadataFetchResult>>;
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
  OIDC_CONTROLLED_EGRESS_REQUIRED: "OIDC discovery requires a controlled server-side egress transport.",
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
  return { ok: false, error: errorFor(code) };
}

function errorFor(code: OidcRuntimeErrorCode): OidcRuntimeError {
  return Object.freeze({ code, message: ERROR_MESSAGES[code] });
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

function ipv4ToInt(host: string): number | null {
  if (!/^\d{1,3}(?:\.\d{1,3}){3}$/.test(host)) {
    return null;
  }
  const parts = host.split(".").map((part) => Number.parseInt(part, 10));
  if (parts.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) {
    return null;
  }
  return parts.reduce((acc, part) => (acc << 8) + part, 0) >>> 0;
}

function ipv4InCidr(value: number, base: string, bits: number): boolean {
  const baseValue = ipv4ToInt(base);
  if (baseValue === null) {
    return false;
  }
  const mask = bits === 0 ? 0 : (0xffffffff << (32 - bits)) >>> 0;
  return (value & mask) === (baseValue & mask);
}

function deniedIpv4Literal(host: string): boolean {
  const value = ipv4ToInt(host);
  if (value === null) {
    return false;
  }
  return [
    ["0.0.0.0", 8],
    ["10.0.0.0", 8],
    ["100.64.0.0", 10],
    ["127.0.0.0", 8],
    ["169.254.0.0", 16],
    ["172.16.0.0", 12],
    ["192.0.0.0", 24],
    ["192.0.2.0", 24],
    ["192.168.0.0", 16],
    ["198.18.0.0", 15],
    ["198.51.100.0", 24],
    ["203.0.113.0", 24],
    ["224.0.0.0", 4],
    ["240.0.0.0", 4]
  ].some(([base, bits]) => ipv4InCidr(value, base as string, bits as number));
}

function deniedIpv6Literal(hostname: string): boolean {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, "");
  if (!host.includes(":")) {
    return false;
  }
  if (host.startsWith("::ffff:")) {
    return true;
  }
  return (
    host === "::" ||
    host === "::1" ||
    host.startsWith("fc") ||
    host.startsWith("fd") ||
    host.startsWith("fe80") ||
    host.startsWith("ff") ||
    host.startsWith("2001:db8")
  );
}

function unsafeLiteralHost(hostname: string): boolean {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, "");
  return host === "localhost" || deniedIpv4Literal(host) || deniedIpv6Literal(host);
}

function dynamicMarkerPresent(value: string): boolean {
  const lower = value.toLowerCase();
  return value.includes("*") || value.includes("{") || value.includes("}") || value.includes("$") || lower.includes("%7b") || lower.includes("%7d");
}

function safeProviderEndpoint(value: unknown, configuration: ValidOidcConfiguration): string | null {
  if (typeof value !== "string" || /[\x00-\x1f\x7f]/.test(value) || dynamicMarkerPresent(value)) {
    return null;
  }
  try {
    const parsed = new URL(value);
    const issuer = new URL(configuration.issuer);
    if (
      parsed.protocol !== "https:" ||
      parsed.username ||
      parsed.password ||
      parsed.search ||
      parsed.hash ||
      parsed.port === "0" ||
      unsafeLiteralHost(parsed.hostname) ||
      parsed.origin !== issuer.origin
    ) {
      return null;
    }
    return `${parsed.origin}${parsed.pathname}`;
  } catch {
    return null;
  }
}

function cloneStringArray(value: unknown): readonly unknown[] | undefined {
  if (!Array.isArray(value)) {
    return undefined;
  }
  return Object.freeze([...value]);
}

function cachedMetadata(metadata: ServerMetadata): CachedProviderMetadata {
  return Object.freeze({
    issuer: metadata.issuer,
    authorization_endpoint: metadata.authorization_endpoint,
    token_endpoint: metadata.token_endpoint,
    jwks_uri: metadata.jwks_uri,
    response_types_supported: cloneStringArray(metadata.response_types_supported),
    grant_types_supported: cloneStringArray(metadata.grant_types_supported),
    code_challenge_methods_supported: cloneStringArray(metadata.code_challenge_methods_supported),
    token_endpoint_auth_methods_supported: cloneStringArray(metadata.token_endpoint_auth_methods_supported),
    scopes_supported: cloneStringArray(metadata.scopes_supported),
    id_token_signing_alg_values_supported: cloneStringArray(metadata.id_token_signing_alg_values_supported)
  });
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

function configuredScopesSupported(metadata: CachedProviderMetadata, configuration: ValidOidcConfiguration): boolean {
  const providerScopes = stringArray(metadata.scopes_supported);
  if (!providerScopes) {
    return true;
  }
  return configuration.scopes.every((scope) => providerScopes.includes(scope));
}

function allowedIdTokenAlgorithms(metadata: CachedProviderMetadata): AllowedIdTokenSigningAlgorithm[] {
  const published = stringArray(metadata.id_token_signing_alg_values_supported) ?? [];
  return ALLOWED_ID_TOKEN_SIGNING_ALGORITHMS.filter((algorithm) => published.includes(algorithm));
}

function validateMetadata(
  metadata: CachedProviderMetadata,
  configuration: ValidOidcConfiguration,
  nowEpochMs: number,
  cacheTtlMs: number
): OidcProviderRuntimeResult {
  if (metadata.issuer !== configuration.issuer) {
    return fail("OIDC_PROVIDER_ISSUER_MISMATCH");
  }

  const authorizationEndpoint = safeProviderEndpoint(metadata.authorization_endpoint, configuration);
  const tokenEndpoint = safeProviderEndpoint(metadata.token_endpoint, configuration);
  const jwksUri = safeProviderEndpoint(metadata.jwks_uri, configuration);
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

function normalizeBoundedPositiveInteger(value: number | undefined, fallback: number, max: number): number {
  if (value === undefined || !Number.isSafeInteger(value) || value <= 0) {
    return fallback;
  }
  return Math.min(value, max);
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

function mapRuntimeError(error: unknown): OidcRuntimeError {
  const networkError = networkErrorFromCause(error);
  if (networkError) {
    return errorFor(networkError.code);
  }
  return errorFor("OIDC_PROVIDER_DISCOVERY_FAILED");
}

async function discoverProviderMetadata(
  configuration: ValidOidcConfiguration,
  clientSecret: string,
  options: LoadOidcProviderRuntimeOptions,
  nowEpochMs: number
): Promise<OidcMetadataFetchResult> {
  if (!options.fetch) {
    return { ok: false, error: errorFor("OIDC_CONTROLLED_EGRESS_REQUIRED") };
  }
  const timeoutMs = normalizeBoundedPositiveInteger(options.timeoutMs, DEFAULT_OIDC_DISCOVERY_TIMEOUT_MS, DEFAULT_OIDC_DISCOVERY_TIMEOUT_MS);
  const boundedFetch = createBoundedOidcDiscoveryFetch({
    fetch: options.fetch,
    timeoutMs,
    maxBodyBytes: normalizeBoundedPositiveInteger(options.maxBodyBytes, DEFAULT_OIDC_DISCOVERY_MAX_BODY_BYTES, DEFAULT_OIDC_DISCOVERY_MAX_BODY_BYTES)
  }) as CustomFetch;

  try {
    const provider = await discovery(
      new URL(configuration.issuer),
      configuration.clientId,
      {
        client_secret: clientSecret,
        redirect_uris: [configuration.redirectUri],
        response_types: ["code"],
        token_endpoint_auth_method: "client_secret_basic"
      },
      ClientSecretBasic(clientSecret),
      {
        [customFetch]: boundedFetch,
        timeout: Math.max(1, Math.ceil(timeoutMs / 1000)),
        algorithm: "oidc"
      }
    );
    return { ok: true, metadata: cachedMetadata(provider.serverMetadata()), fetchedAtEpochMs: nowEpochMs };
  } catch (error) {
    return { ok: false, error: mapRuntimeError(error) };
  }
}

export async function loadOidcProviderRuntime(
  configuration: unknown,
  options: LoadOidcProviderRuntimeOptions = {}
): Promise<OidcProviderRuntimeResult> {
  if (!isValidOidcConfiguration(configuration)) {
    return fail("OIDC_CONFIGURATION_INVALID");
  }
  const clientSecret = validatedOidcClientSecret(configuration);
  if (!clientSecret) {
    return fail("OIDC_CONFIGURATION_INVALID");
  }

  const nowEpochMs = options.now?.() ?? Date.now();
  const cacheTtlMs = normalizeBoundedPositiveInteger(options.cacheTtlMs, DEFAULT_OIDC_RUNTIME_CACHE_TTL_MS, MAX_OIDC_RUNTIME_CACHE_TTL_MS);
  const maxCacheEntries = normalizeBoundedPositiveInteger(options.maxCacheEntries, DEFAULT_OIDC_RUNTIME_MAX_CACHE_ENTRIES, MAX_OIDC_RUNTIME_CACHE_ENTRIES);
  const cache = options.cache ?? defaultRuntimeCache;
  const cacheKey = configuration.issuer;
  const cached = cache.entries.get(cacheKey);
  if (cached && cached.expiresAtEpochMs > nowEpochMs) {
    return validateMetadata(cached.metadata, configuration, nowEpochMs, cacheTtlMs);
  }
  cache.entries.delete(cacheKey);

  const inFlight = cache.inflight.get(cacheKey);
  if (inFlight) {
    const result = await inFlight;
    return result.ok ? validateMetadata(result.metadata, configuration, nowEpochMs, cacheTtlMs) : { ok: false, error: result.error };
  }

  const work = discoverProviderMetadata(configuration, clientSecret, options, nowEpochMs).finally(() => {
    cache.inflight.delete(cacheKey);
  });
  cache.inflight.set(cacheKey, work);
  const result = await work;
  if (!result.ok) {
    return { ok: false, error: result.error };
  }

  const existing = cache.entries.get(cacheKey);
  if (existing && existing.expiresAtEpochMs > result.fetchedAtEpochMs) {
    return validateMetadata(existing.metadata, configuration, nowEpochMs, cacheTtlMs);
  }

  const validated = validateMetadata(result.metadata, configuration, nowEpochMs, cacheTtlMs);
  if (!validated.ok) {
    return validated;
  }
  cache.entries.set(cacheKey, {
    metadata: result.metadata,
    expiresAtEpochMs: result.fetchedAtEpochMs + cacheTtlMs
  });
  evictOldestEntries(cache, maxCacheEntries);
  return validated;
}

export const oidcRuntimeDiscoveryPolicy = Object.freeze({
  discoveryOnly: false,
  productionDiscoveryRequiresControlledEgress: true,
  loginRoutesImplemented: true,
  callbackRoutesImplemented: true,
  tokenExchangeImplemented: true,
  sessionsImplemented: true,
  tenantMembershipImplemented: true,
  supportedClientAuthenticationMethod: SUPPORTED_OIDC_CLIENT_AUTHENTICATION_METHOD,
  supportedScopes: Object.freeze(["openid", "profile", "email"] as const),
  allowedIdTokenSigningAlgorithms: Object.freeze([...ALLOWED_ID_TOKEN_SIGNING_ALGORITHMS])
});
