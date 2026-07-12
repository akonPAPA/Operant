/**
 * Server-only BFF configuration. Never expose gateway or session secrets to the browser.
 * This module is Edge-safe: env reads only, no Node-only imports.
 */
import { isProductionLikeDeployment, isProductionNodeRuntime } from "./bff-deployment-profile.ts";
import { decodeGatewaySharedSecret, readGatewaySharedSecretEnv } from "./bff-gateway-key.ts";

const DEFAULT_CORE = "http://127.0.0.1:8080";

export type BffRuntimeMode = "demo-dev" | "bff-production" | "unavailable";

function envFlag(name: string): string {
  // Bracket access avoids Next/SWC compile-time inlining of process.env.NAME patterns.
  return String(process.env[name] ?? "").trim();
}

function isDemoMode(): boolean {
  const privateDemo = envFlag("ORDERPILOT_DEMO_MODE");
  if (privateDemo === "true") {
    return true;
  }
  if (privateDemo === "false") {
    return false;
  }
  return envFlag("NEXT_PUBLIC_ORDERPILOT_DEMO_MODE") === "true";
}

function isBffEnabled(): boolean {
  return envFlag("ORDERPILOT_BFF_ENABLED") === "true";
}

export function bffRuntimeMode(): BffRuntimeMode {
  // Private ORDERPILOT_DEMO_MODE=false must win over a build-time-inlined NEXT_PUBLIC demo flag
  // so production artifacts and E2E can enable BFF even when .env.local baked demo=true into the build.
  if (isBffEnabled() && !isDemoMode()) {
    return "bff-production";
  }
  if (isProductionNodeRuntime() || process.env.NODE_ENV === "production") {
    return "unavailable";
  }
  return "demo-dev";
}

/** Demo/dev only base URL (may fall back to loopback). Never used by the production proxy. */
export function coreApiInternalBaseUrl(): string {
  return (process.env.CORE_API_BASE_URL ?? DEFAULT_CORE).replace(/\/$/, "");
}

function isLiteralLoopbackHost(hostname: string): boolean {
  return hostname === "127.0.0.1" || hostname === "[::1]" || hostname === "::1";
}

export type StrictIntegerResult =
  | { ok: true; value: number }
  | { ok: false; reason: string };

export function parseStrictBoundedInteger(
  raw: string | undefined,
  name: string,
  options: { defaultValue?: number; min: number; max: number }
): StrictIntegerResult {
  if (raw === undefined) {
    if (options.defaultValue === undefined) {
      return { ok: false, reason: `${name} is required` };
    }
    return { ok: true, value: options.defaultValue };
  }
  const trimmed = raw.trim();
  if (trimmed === "") {
    return { ok: false, reason: `${name} must be a decimal integer` };
  }
  if (!/^[0-9]+$/.test(trimmed)) {
    return { ok: false, reason: `${name} must be a decimal integer` };
  }
  const value = Number(trimmed);
  if (!Number.isSafeInteger(value)) {
    return { ok: false, reason: `${name} must be a safe integer` };
  }
  if (value < options.min) {
    return { ok: false, reason: `${name} must be at least ${options.min}` };
  }
  if (value > options.max) {
    return { ok: false, reason: `${name} must not exceed ${options.max}` };
  }
  return { ok: true, value };
}

/**
 * Validated internal Core origin for the BFF proxy.
 * Production-like: https only, or http://127.0.0.1 / http://[::1] only.
 * Rejects userinfo, fragments, non-http(s), and plain-http non-loopback hosts.
 * `localhost` is intentionally NOT treated as loopback.
 */
export function validatedCoreApiInternalBaseUrl(): string | null {
  const raw = process.env.CORE_API_BASE_URL?.trim();
  if (!raw) {
    return isProductionLikeDeployment() ? null : DEFAULT_CORE;
  }
  try {
    const parsed = new URL(raw);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
      return null;
    }
    if (parsed.username || parsed.password) {
      return null;
    }
    if (parsed.search || parsed.hash) {
      return null;
    }
    if (parsed.pathname !== "/" && parsed.pathname !== "") {
      return null;
    }
    if (parsed.protocol === "http:") {
      // Production-like: only literal loopback HTTP. Local/test may use explicit http Core URLs.
      if (isProductionLikeDeployment() && !isLiteralLoopbackHost(parsed.hostname)) {
        return null;
      }
    }
    return parsed.origin;
  } catch {
    return null;
  }
}

/**
 * Validated gateway shared secret (64 hex -> 32 raw bytes). Returns the configured hex string when
 * valid; empty string when missing/invalid (callers fail closed).
 */
export function bffGatewaySharedSecret(): string {
  const raw = readGatewaySharedSecretEnv();
  const decoded = decodeGatewaySharedSecret(raw);
  return decoded.ok ? raw.trim() : "";
}

export function bffGatewayClockSkewSeconds(): number {
  const parsed = parseStrictBoundedInteger(
    process.env.ORDERPILOT_GATEWAY_HEADER_AUTH_CLOCK_SKEW_SECONDS,
    "ORDERPILOT_GATEWAY_HEADER_AUTH_CLOCK_SKEW_SECONDS",
    { defaultValue: 300, min: 1, max: 3_600 }
  );
  if (!parsed.ok) {
    throw new Error(parsed.reason);
  }
  return parsed.value;
}

/** Upstream Core timeout for the BFF proxy, bounded to [1s, 120s]. */
export function bffUpstreamTimeoutMs(): number {
  const parsed = parseStrictBoundedInteger(
    process.env.ORDERPILOT_BFF_UPSTREAM_TIMEOUT_MS,
    "ORDERPILOT_BFF_UPSTREAM_TIMEOUT_MS",
    { defaultValue: 30_000, min: 1_000, max: 120_000 }
  );
  if (!parsed.ok) {
    throw new Error(parsed.reason);
  }
  return parsed.value;
}

export function bffCookieSecure(): boolean {
  return isProductionNodeRuntime();
}

/**
 * Exact public browser origin for CSRF Origin/Referer checks.
 * Production-like deployments require https://... with path "/", no query/fragment/userinfo.
 * Local/test may allow explicit loopback http origins.
 */
export function bffPublicOrigin(): string | null {
  const raw = process.env.ORDERPILOT_PUBLIC_ORIGIN?.trim();
  if (!raw) {
    return null;
  }
  try {
    const parsed = new URL(raw);
    if (parsed.username || parsed.password) {
      return null;
    }
    if (parsed.search || parsed.hash) {
      return null;
    }
    if (parsed.pathname !== "/" && parsed.pathname !== "") {
      return null;
    }
    if (raw !== parsed.origin) {
      return null;
    }
    if (isProductionLikeDeployment()) {
      if (parsed.protocol !== "https:") {
        return null;
      }
    } else if (parsed.protocol === "http:") {
      // Local/test only: allow explicit loopback and localhost browser origins for E2E.
      // Production-like profiles already required https above.
      const host = parsed.hostname;
      if (!isLiteralLoopbackHost(host) && host !== "localhost") {
        return null;
      }
    } else if (parsed.protocol !== "https:") {
      return null;
    }
    return parsed.origin;
  } catch {
    return null;
  }
}

export const BFF_SESSION_COOKIE = "op_session";
export const BFF_CSRF_COOKIE = "op_csrf";
export const BFF_CSRF_HEADER = "X-OP-CSRF-Token";
