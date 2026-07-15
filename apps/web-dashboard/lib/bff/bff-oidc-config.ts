const PRODUCTION_LIKE_DEPLOY_PROFILES = new Set(["prod", "production", "cloud", "staging"]);
const MAX_OIDC_SCOPE_COUNT = 8;
const MAX_OIDC_SCOPE_LENGTH = 64;

export const OIDC_RUNTIME_IMPLEMENTED = false;
export const SUPPORTED_OIDC_SCOPES = ["openid", "profile", "email"] as const;
export const SUPPORTED_OIDC_CLIENT_AUTHENTICATION_METHOD = "CLIENT_SECRET_BASIC";

export type OidcReadinessState =
  | "DISABLED"
  | "INVALID_CONFIGURATION"
  | "VALID_CONFIGURATION_RUNTIME_NOT_IMPLEMENTED"
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

export type OidcConfiguration = {
  issuer: string;
  clientId: string;
  clientSecret: string;
  redirectUri: string;
  postLogoutRedirectUri: string;
  scopes: OidcScope[];
  clientAuthenticationMethod: OidcClientAuthenticationMethod;
  runtimeImplemented: false;
};

export type OidcConfigurationStatus =
  | { state: "DISABLED"; enabled: false; runtimeImplemented: false }
  | {
      state: "INVALID_CONFIGURATION";
      enabled: boolean;
      reasonCode: OidcConfigErrorCode;
      runtimeImplemented: false;
    }
  | {
      state: "VALID_CONFIGURATION_RUNTIME_NOT_IMPLEMENTED";
      enabled: true;
      configuration: OidcConfiguration;
      runtimeImplemented: false;
    };

export type OidcConfigurationDiagnostic = {
  enabled: boolean;
  state: OidcReadinessState;
  runtimeImplemented: false;
  reasonCode?: OidcConfigErrorCode;
};

export type PublicAuthenticationCapability = {
  authenticationAvailable: false;
  runtimeImplemented: false;
  state: OidcReadinessState;
};

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

function loopbackOrLocalhost(hostname: string): boolean {
  const host = hostname.toLowerCase();
  return (
    host === "localhost" ||
    host === "127.0.0.1" ||
    host.startsWith("127.") ||
    host === "::1" ||
    host === "[::1]" ||
    host === "::" ||
    host === "[::]" ||
    host === "0.0.0.0"
  );
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

function denyPublicSerialization<T extends OidcConfigurationStatus>(status: T): T {
  Object.defineProperty(status, "toJSON", {
    value() {
      throw new Error("OIDC_CONFIGURATION_STATUS_NOT_PUBLIC");
    },
    enumerable: false
  });
  return status;
}

function invalid(reasonCode: OidcConfigErrorCode, enabled = true): OidcConfigurationStatus {
  return denyPublicSerialization({ state: "INVALID_CONFIGURATION", enabled, reasonCode, runtimeImplemented: false });
}

export function readOidcConfigurationStatus(env: EnvironmentValues = process.env): OidcConfigurationStatus {
  const enabledRaw = envValue(env, "ORDERPILOT_OIDC_ENABLED");
  if (!enabledRaw || enabledRaw === "false") {
    return denyPublicSerialization({ state: "DISABLED", enabled: false, runtimeImplemented: false });
  }
  if (enabledRaw !== "true") {
    return invalid("ENABLED_FLAG_INVALID", false);
  }

  const publicOrigin = validatePublicOrigin(env);
  if (!publicOrigin.ok) return invalid(publicOrigin.reasonCode);
  const issuer = validateIssuer(env);
  if (!issuer.ok) return invalid(issuer.reasonCode);
  const clientId = validateClientId(env);
  if (!clientId.ok) return invalid(clientId.reasonCode);
  const clientSecret = validateClientSecret(env);
  if (!clientSecret.ok) return invalid(clientSecret.reasonCode);
  const redirectUri = validateRedirectUri(env, publicOrigin.origin);
  if (!redirectUri.ok) return invalid(redirectUri.reasonCode);
  const postLogoutRedirectUri = validatePostLogoutRedirectUri(env, publicOrigin.origin);
  if (!postLogoutRedirectUri.ok) return invalid(postLogoutRedirectUri.reasonCode);
  const scopes = validateScopes(env);
  if (!scopes.ok) return invalid(scopes.reasonCode);
  const clientAuthenticationMethod = validateClientAuthenticationMethod(env);
  if (!clientAuthenticationMethod.ok) return invalid(clientAuthenticationMethod.reasonCode);

  return denyPublicSerialization({
    state: "VALID_CONFIGURATION_RUNTIME_NOT_IMPLEMENTED",
    enabled: true,
    runtimeImplemented: OIDC_RUNTIME_IMPLEMENTED,
    configuration: {
      issuer: issuer.issuer,
      clientId: clientId.clientId,
      clientSecret: clientSecret.clientSecret,
      redirectUri: redirectUri.redirectUri,
      postLogoutRedirectUri: postLogoutRedirectUri.postLogoutRedirectUri,
      scopes: scopes.scopes,
      clientAuthenticationMethod: clientAuthenticationMethod.clientAuthenticationMethod,
      runtimeImplemented: OIDC_RUNTIME_IMPLEMENTED
    }
  });
}

export function oidcReadinessState(env: EnvironmentValues = process.env): OidcReadinessState {
  return readOidcConfigurationStatus(env).state;
}

export function oidcConfigurationDiagnostic(status = readOidcConfigurationStatus()): OidcConfigurationDiagnostic {
  return {
    enabled: status.enabled,
    state: status.state,
    runtimeImplemented: false,
    ...(status.state === "INVALID_CONFIGURATION" ? { reasonCode: status.reasonCode } : {})
  };
}

export function publicAuthenticationCapability(status = readOidcConfigurationStatus()): PublicAuthenticationCapability {
  return {
    authenticationAvailable: false,
    runtimeImplemented: false,
    state: status.state
  };
}
