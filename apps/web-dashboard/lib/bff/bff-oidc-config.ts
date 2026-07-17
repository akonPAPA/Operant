const PRODUCTION_LIKE_DEPLOY_PROFILES = new Set(["prod", "production", "cloud", "staging"]);
const MAX_OIDC_SCOPE_COUNT = 8;
const MAX_OIDC_SCOPE_LENGTH = 64;
export const OIDC_RUNTIME_IMPLEMENTED = true;
export const SUPPORTED_OIDC_SCOPES = ["openid", "profile", "email"] as const;
export const SUPPORTED_OIDC_CLIENT_AUTHENTICATION_METHOD = "CLIENT_SECRET_BASIC";

export type OidcReadinessState =
  | "DISABLED"
  | "INVALID_CONFIGURATION"
  | "READY";

export type OidcClientAuthenticationMethod = typeof SUPPORTED_OIDC_CLIENT_AUTHENTICATION_METHOD;
export type OidcScope = (typeof SUPPORTED_OIDC_SCOPES)[number];

export type OidcConfigErrorCode =
  | "ENABLED_FLAG_INVALID"
  | "PUBLIC_ORIGIN_REQUIRED"
  | "PUBLIC_ORIGIN_INVALID"
  | "PUBLIC_ORIGIN_HTTPS_REQUIRED"
  | "ISSUER_REQUIRED"
  | "ISSUER_INVALID"
  | "ISSUER_HTTPS_REQUIRED"
  | "ISSUER_PRODUCTION_HOST_INVALID"
  | "CLIENT_ID_REQUIRED"
  | "CLIENT_ID_INVALID"
  | "CLIENT_ID_PLACEHOLDER"
  | "CLIENT_SECRET_REQUIRED"
  | "CLIENT_SECRET_INVALID"
  | "CLIENT_SECRET_PLACEHOLDER"
  | "REDIRECT_URI_REQUIRED"
  | "REDIRECT_URI_INVALID"
  | "REDIRECT_URI_HTTPS_REQUIRED"
  | "REDIRECT_URI_PRODUCTION_HOST_INVALID"
  | "REDIRECT_URI_ORIGIN_MISMATCH"
  | "REDIRECT_URI_PATH_INVALID"
  | "POST_LOGOUT_REDIRECT_URI_INVALID"
  | "POST_LOGOUT_REDIRECT_URI_HTTPS_REQUIRED"
  | "POST_LOGOUT_REDIRECT_URI_PRODUCTION_HOST_INVALID"
  | "POST_LOGOUT_REDIRECT_URI_ORIGIN_MISMATCH"
  | "POST_LOGOUT_REDIRECT_URI_PATH_INVALID"
  | "SCOPE_OPENID_REQUIRED"
  | "SCOPE_INVALID"
  | "SCOPE_UNSUPPORTED"
  | "CLIENT_AUTHENTICATION_METHOD_UNSUPPORTED";

type EnvironmentValues = Record<string, string | undefined>;

export type OidcConfiguration = Readonly<{
  issuer: string;
  clientId: string;
  redirectUri: string;
  postLogoutRedirectUri: string;
  scopes: readonly OidcScope[];
  clientAuthenticationMethod: OidcClientAuthenticationMethod;
  policyFingerprint: string;
  runtimeImplemented: true;
}>;

export type ValidOidcConfiguration = OidcConfiguration & { readonly __validOidcConfigurationBrand?: never };

export type OidcConfigurationStatus =
  | { state: "DISABLED"; enabled: false; runtimeImplemented: false }
  | {
      state: "INVALID_CONFIGURATION";
      enabled: boolean;
      reasonCode: OidcConfigErrorCode;
      runtimeImplemented: false;
    }
  | {
      state: "READY";
      enabled: true;
      runtimeImplemented: true;
      policyFingerprint: string;
    };

export type OidcConfigurationDiagnostic = {
  enabled: boolean;
  state: OidcReadinessState;
  runtimeImplemented: boolean;
  reasonCode?: OidcConfigErrorCode;
  policyFingerprint?: string;
};

export type OidcValidatedConfigurationResult =
  | { ok: true; configuration: ValidOidcConfiguration }
  | { ok: false; status: OidcConfigurationStatus };

export type PublicAuthenticationCapability = {
  authenticationAvailable: boolean;
  runtimeImplemented: boolean;
  state: OidcReadinessState;
};

const validatedConfigurations = new WeakSet<object>();
const oidcClientSecrets = new WeakMap<object, string>();

function envValue(env: EnvironmentValues, name: string): string | undefined {
  const value = env[name]?.trim();
  return value ? value : undefined;
}

function productionLike(env: EnvironmentValues): boolean {
  if (envValue(env, "NODE_ENV") === "production") {
    return true;
  }
  const profile = envValue(env, "ORDERPILOT_DEPLOY_PROFILE")?.toLowerCase() ?? "";
  return PRODUCTION_LIKE_DEPLOY_PROFILES.has(profile);
}

function dynamicMarkerPresent(value: string): boolean {
  const lower = value.toLowerCase();
  return (
    value.includes("*") ||
    value.includes("{") ||
    value.includes("}") ||
    value.includes("$") ||
    lower.includes("%7b") ||
    lower.includes("%7d")
  );
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

function loopbackOrLocalhost(hostname: string): boolean {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, "");
  return host === "localhost" || deniedIpv4Literal(host) || deniedIpv6Literal(host);
}

function parseBoundedUrl(raw: string): URL | null {
  if (/[\u0000-\u001f\u007f]/.test(raw)) {
    return null;
  }
  if (dynamicMarkerPresent(raw)) {
    return null;
  }
  try {
    const parsed = new URL(raw);
    if (parsed.protocol !== "https:" && parsed.protocol !== "http:") {
      return null;
    }
    if (parsed.username || parsed.password || parsed.search || parsed.hash) {
      return null;
    }
    if (parsed.port === "0") {
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

function validatePublicOrigin(env: EnvironmentValues): { ok: true; origin: string } | { ok: false; reasonCode: OidcConfigErrorCode } {
  const raw = envValue(env, "ORDERPILOT_PUBLIC_ORIGIN");
  if (!raw) {
    return { ok: false, reasonCode: "PUBLIC_ORIGIN_REQUIRED" };
  }
  const parsed = parseBoundedUrl(raw);
  if (!parsed || raw !== parsed.origin || parsed.pathname !== "/") {
    return { ok: false, reasonCode: "PUBLIC_ORIGIN_INVALID" };
  }
  if (productionLike(env) && parsed.protocol !== "https:") {
    return { ok: false, reasonCode: "PUBLIC_ORIGIN_HTTPS_REQUIRED" };
  }
  return { ok: true, origin: parsed.origin };
}

function normalizeIssuer(parsed: URL): string {
  const path = parsed.pathname === "/" ? "" : parsed.pathname.replace(/\/+$/, "");
  return `${parsed.origin}${path}`;
}

function validateIssuer(env: EnvironmentValues): { ok: true; issuer: string } | { ok: false; reasonCode: OidcConfigErrorCode } {
  const raw = envValue(env, "ORDERPILOT_OIDC_ISSUER");
  if (!raw) {
    return { ok: false, reasonCode: "ISSUER_REQUIRED" };
  }
  const parsed = parseBoundedUrl(raw);
  if (!parsed) {
    return { ok: false, reasonCode: "ISSUER_INVALID" };
  }
  if (productionLike(env)) {
    if (parsed.protocol !== "https:") {
      return { ok: false, reasonCode: "ISSUER_HTTPS_REQUIRED" };
    }
    if (loopbackOrLocalhost(parsed.hostname)) {
      return { ok: false, reasonCode: "ISSUER_PRODUCTION_HOST_INVALID" };
    }
  }
  return { ok: true, issuer: normalizeIssuer(parsed) };
}

function placeholderSecret(value: string): boolean {
  const normalized = value.trim().toLowerCase();
  return new Set([
    "change-me",
    "changeme",
    "change-me-local-dev-only",
    "replace-me",
    "placeholder",
    "todo",
    "secret",
    "client-secret",
    "your-client-secret"
  ]).has(normalized);
}

function placeholderClientId(value: string): boolean {
  const normalized = value.trim().toLowerCase();
  return new Set([
    "change-me",
    "changeme",
    "replace-me",
    "placeholder",
    "todo",
    "client-id",
    "example-client-id",
    "your-client-id"
  ]).has(normalized);
}

function invalidTextValue(value: string, maxLength: number): boolean {
  return value.length > maxLength || /[\u0000-\u001f\u007f]/.test(value) || dynamicMarkerPresent(value);
}

function validateClientId(env: EnvironmentValues): { ok: true; clientId: string } | { ok: false; reasonCode: OidcConfigErrorCode } {
  const clientId = envValue(env, "ORDERPILOT_OIDC_CLIENT_ID");
  if (!clientId) {
    return { ok: false, reasonCode: "CLIENT_ID_REQUIRED" };
  }
  if (invalidTextValue(clientId, 256)) {
    return { ok: false, reasonCode: "CLIENT_ID_INVALID" };
  }
  if (placeholderClientId(clientId)) {
    return { ok: false, reasonCode: "CLIENT_ID_PLACEHOLDER" };
  }
  return { ok: true, clientId };
}

function validateClientSecret(env: EnvironmentValues): { ok: true; clientSecret: string } | { ok: false; reasonCode: OidcConfigErrorCode } {
  const clientSecret = envValue(env, "ORDERPILOT_OIDC_CLIENT_SECRET");
  if (!clientSecret) {
    return { ok: false, reasonCode: "CLIENT_SECRET_REQUIRED" };
  }
  if (invalidTextValue(clientSecret, 4096)) {
    return { ok: false, reasonCode: "CLIENT_SECRET_INVALID" };
  }
  if (placeholderSecret(clientSecret)) {
    return { ok: false, reasonCode: "CLIENT_SECRET_PLACEHOLDER" };
  }
  return { ok: true, clientSecret };
}

function validateRedirectUri(
  env: EnvironmentValues,
  publicOrigin: string
): { ok: true; redirectUri: string } | { ok: false; reasonCode: OidcConfigErrorCode } {
  const raw = envValue(env, "ORDERPILOT_OIDC_REDIRECT_URI");
  if (!raw) {
    return { ok: false, reasonCode: "REDIRECT_URI_REQUIRED" };
  }
  const parsed = parseBoundedUrl(raw);
  if (!parsed) {
    return { ok: false, reasonCode: "REDIRECT_URI_INVALID" };
  }
  if (productionLike(env)) {
    if (parsed.protocol !== "https:") {
      return { ok: false, reasonCode: "REDIRECT_URI_HTTPS_REQUIRED" };
    }
    if (loopbackOrLocalhost(parsed.hostname)) {
      return { ok: false, reasonCode: "REDIRECT_URI_PRODUCTION_HOST_INVALID" };
    }
  }
  if (parsed.origin !== publicOrigin) {
    return { ok: false, reasonCode: "REDIRECT_URI_ORIGIN_MISMATCH" };
  }
  if (parsed.pathname !== "/api/auth/oidc/callback") {
    return { ok: false, reasonCode: "REDIRECT_URI_PATH_INVALID" };
  }
  return { ok: true, redirectUri: `${parsed.origin}${parsed.pathname}` };
}

function validatePostLogoutRedirectUri(
  env: EnvironmentValues,
  publicOrigin: string
): { ok: true; postLogoutRedirectUri: string } | { ok: false; reasonCode: OidcConfigErrorCode } {
  const raw = envValue(env, "ORDERPILOT_OIDC_POST_LOGOUT_REDIRECT_URI") ?? `${publicOrigin}/login`;
  const parsed = parseBoundedUrl(raw);
  if (!parsed) {
    return { ok: false, reasonCode: "POST_LOGOUT_REDIRECT_URI_INVALID" };
  }
  if (productionLike(env)) {
    if (parsed.protocol !== "https:") {
      return { ok: false, reasonCode: "POST_LOGOUT_REDIRECT_URI_HTTPS_REQUIRED" };
    }
    if (loopbackOrLocalhost(parsed.hostname)) {
      return { ok: false, reasonCode: "POST_LOGOUT_REDIRECT_URI_PRODUCTION_HOST_INVALID" };
    }
  }
  if (parsed.origin !== publicOrigin) {
    return { ok: false, reasonCode: "POST_LOGOUT_REDIRECT_URI_ORIGIN_MISMATCH" };
  }
  if (parsed.pathname !== "/login") {
    return { ok: false, reasonCode: "POST_LOGOUT_REDIRECT_URI_PATH_INVALID" };
  }
  return { ok: true, postLogoutRedirectUri: `${parsed.origin}${parsed.pathname}` };
}

function validateScopes(env: EnvironmentValues): { ok: true; scopes: OidcScope[] } | { ok: false; reasonCode: OidcConfigErrorCode } {
  const raw = envValue(env, "ORDERPILOT_OIDC_SCOPES") ?? "openid profile email";
  const seen = new Set<string>();
  const scopes = raw.split(/\s+/).filter(Boolean);
  if (scopes.length === 0 || scopes.length > MAX_OIDC_SCOPE_COUNT) {
    return { ok: false, reasonCode: "SCOPE_INVALID" };
  }
  if (!scopes.includes("openid")) {
    return { ok: false, reasonCode: "SCOPE_OPENID_REQUIRED" };
  }
  for (const scope of scopes) {
    if (invalidTextValue(scope, MAX_OIDC_SCOPE_LENGTH)) {
      return { ok: false, reasonCode: "SCOPE_INVALID" };
    }
    if (!(SUPPORTED_OIDC_SCOPES as readonly string[]).includes(scope)) {
      return { ok: false, reasonCode: "SCOPE_UNSUPPORTED" };
    }
    seen.add(scope);
  }
  return { ok: true, scopes: [...seen] as OidcScope[] };
}

function validateClientAuthenticationMethod(
  env: EnvironmentValues
): { ok: true; clientAuthenticationMethod: OidcClientAuthenticationMethod } | { ok: false; reasonCode: OidcConfigErrorCode } {
  const method = envValue(env, "ORDERPILOT_OIDC_CLIENT_AUTHENTICATION_METHOD") ?? SUPPORTED_OIDC_CLIENT_AUTHENTICATION_METHOD;
  if (method !== SUPPORTED_OIDC_CLIENT_AUTHENTICATION_METHOD) {
    return { ok: false, reasonCode: "CLIENT_AUTHENTICATION_METHOD_UNSUPPORTED" };
  }
  return { ok: true, clientAuthenticationMethod: method };
}

function publicSerializationDenied(name: string): () => never {
  return () => {
    throw new Error(name);
  };
}

function denyPublicSerialization<T extends object>(value: T, errorName: string): T {
  Object.defineProperty(value, "toJSON", {
    value: publicSerializationDenied(errorName),
    enumerable: false
  });
  return value;
}

function invalid(reasonCode: OidcConfigErrorCode, enabled = true): OidcConfigurationStatus {
  return denyPublicSerialization({ state: "INVALID_CONFIGURATION", enabled, reasonCode, runtimeImplemented: false }, "OIDC_CONFIGURATION_STATUS_NOT_PUBLIC");
}

function fingerprintHash(value: string): string {
  let hash = 0xcbf29ce484222325n;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= BigInt(value.charCodeAt(index));
    hash = BigInt.asUintN(64, hash * 0x100000001b3n);
  }
  return hash.toString(16).padStart(16, "0");
}

function policyFingerprint(configuration: Omit<OidcConfiguration, "policyFingerprint" | "runtimeImplemented">): string {
  return `oidc-policy-v1:${fingerprintHash(JSON.stringify({
    issuer: configuration.issuer,
    clientId: configuration.clientId,
    redirectUri: configuration.redirectUri,
    postLogoutRedirectUri: configuration.postLogoutRedirectUri,
    scopes: configuration.scopes,
    clientAuthenticationMethod: configuration.clientAuthenticationMethod
  }))}`;
}

function buildValidatedConfiguration(env: EnvironmentValues): OidcValidatedConfigurationResult {
  const enabledRaw = envValue(env, "ORDERPILOT_OIDC_ENABLED");
  if (!enabledRaw || enabledRaw === "false") {
    return { ok: false, status: denyPublicSerialization({ state: "DISABLED", enabled: false, runtimeImplemented: false }, "OIDC_CONFIGURATION_STATUS_NOT_PUBLIC") };
  }
  if (enabledRaw !== "true") {
    return { ok: false, status: invalid("ENABLED_FLAG_INVALID", false) };
  }

  const publicOrigin = validatePublicOrigin(env);
  if (!publicOrigin.ok) return { ok: false, status: invalid(publicOrigin.reasonCode) };
  const issuer = validateIssuer(env);
  if (!issuer.ok) return { ok: false, status: invalid(issuer.reasonCode) };
  const clientId = validateClientId(env);
  if (!clientId.ok) return { ok: false, status: invalid(clientId.reasonCode) };
  const clientSecret = validateClientSecret(env);
  if (!clientSecret.ok) return { ok: false, status: invalid(clientSecret.reasonCode) };
  const redirectUri = validateRedirectUri(env, publicOrigin.origin);
  if (!redirectUri.ok) return { ok: false, status: invalid(redirectUri.reasonCode) };
  const postLogoutRedirectUri = validatePostLogoutRedirectUri(env, publicOrigin.origin);
  if (!postLogoutRedirectUri.ok) return { ok: false, status: invalid(postLogoutRedirectUri.reasonCode) };
  const scopes = validateScopes(env);
  if (!scopes.ok) return { ok: false, status: invalid(scopes.reasonCode) };
  const clientAuthenticationMethod = validateClientAuthenticationMethod(env);
  if (!clientAuthenticationMethod.ok) return { ok: false, status: invalid(clientAuthenticationMethod.reasonCode) };

  const safeConfigurationWithoutFingerprint = {
    issuer: issuer.issuer,
    clientId: clientId.clientId,
    redirectUri: redirectUri.redirectUri,
    postLogoutRedirectUri: postLogoutRedirectUri.postLogoutRedirectUri,
    scopes: Object.freeze([...scopes.scopes]) as readonly OidcScope[],
    clientAuthenticationMethod: clientAuthenticationMethod.clientAuthenticationMethod
  };
  const configuration = Object.freeze(denyPublicSerialization({
    ...safeConfigurationWithoutFingerprint,
    policyFingerprint: policyFingerprint(safeConfigurationWithoutFingerprint),
    runtimeImplemented: OIDC_RUNTIME_IMPLEMENTED
  }, "OIDC_CONFIGURATION_NOT_PUBLIC")) as ValidOidcConfiguration;
  validatedConfigurations.add(configuration);
  oidcClientSecrets.set(configuration, clientSecret.clientSecret);
  return { ok: true, configuration };
}

export function loadValidatedOidcConfiguration(env: EnvironmentValues = process.env): OidcValidatedConfigurationResult {
  return buildValidatedConfiguration(env);
}

export function validOidcConfiguration(env: EnvironmentValues = process.env): ValidOidcConfiguration | null {
  const result = loadValidatedOidcConfiguration(env);
  return result.ok ? result.configuration : null;
}

export function isValidOidcConfiguration(value: unknown): value is ValidOidcConfiguration {
  return Boolean(value && typeof value === "object" && validatedConfigurations.has(value));
}

export function validatedOidcClientSecret(configuration: ValidOidcConfiguration): string | null {
  if (!isValidOidcConfiguration(configuration)) {
    return null;
  }
  return oidcClientSecrets.get(configuration) ?? null;
}

export function readOidcConfigurationStatus(env: EnvironmentValues = process.env): OidcConfigurationStatus {
  const result = loadValidatedOidcConfiguration(env);
  if (!result.ok) {
    return result.status;
  }
  return denyPublicSerialization({
    state: "READY",
    enabled: true,
    runtimeImplemented: OIDC_RUNTIME_IMPLEMENTED,
    policyFingerprint: result.configuration.policyFingerprint
  }, "OIDC_CONFIGURATION_STATUS_NOT_PUBLIC");
}

export function oidcReadinessState(env: EnvironmentValues = process.env): OidcReadinessState {
  return readOidcConfigurationStatus(env).state;
}

export function readOidcConfigurationDiagnostic(env: EnvironmentValues = process.env): OidcConfigurationDiagnostic {
  return oidcConfigurationDiagnostic(readOidcConfigurationStatus(env));
}

export function oidcConfigurationDiagnostic(status = readOidcConfigurationStatus()): OidcConfigurationDiagnostic {
  return {
    enabled: status.enabled,
    state: status.state,
    runtimeImplemented: status.runtimeImplemented,
    ...(status.state === "INVALID_CONFIGURATION" ? { reasonCode: status.reasonCode } : {}),
    ...(status.state === "READY" ? { policyFingerprint: status.policyFingerprint } : {})
  };
}

export function publicAuthenticationCapability(status = readOidcConfigurationStatus()): PublicAuthenticationCapability {
  return {
    authenticationAvailable: status.state === "READY",
    runtimeImplemented: status.runtimeImplemented,
    state: status.state
  };
}
