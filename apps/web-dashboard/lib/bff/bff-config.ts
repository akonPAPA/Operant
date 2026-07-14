/**
 * Server-only BFF configuration. Never expose gateway or session secrets to the browser.
 * This module is Edge-safe: env reads only, no Node-only imports.
 */
import {
  isProductionLikeDeployment,
  isProductionNodeRuntime,
  isSecureCookieDeployment
} from "./bff-deployment-profile.ts";
import { decodeGatewaySharedSecret, readGatewaySharedSecretEnv } from "./bff-gateway-key.ts";
import { bffRuntimeMode, parseStrictBoundedInteger } from "./bff-public-config.ts";

// F06: browser-safe constants and helpers live in bff-public-config.ts (no gateway key / Node APIs).
// This server-only config module re-exports them so server callers keep a single import site, while
// the browser import graph reaches bff-public-config.ts directly and never this module.
export {
  BFF_SESSION_COOKIE,
  BFF_CSRF_COOKIE,
  BFF_CSRF_HEADER,
  bffRuntimeMode,
  parseStrictBoundedInteger,
  type BffRuntimeMode,
  type StrictIntegerResult
} from "./bff-public-config.ts";

const DEFAULT_CORE = "http://127.0.0.1:8080";

/** Demo/dev only base URL (may fall back to loopback). Never used by the production proxy. */
export function coreApiInternalBaseUrl(): string {
  return (process.env.CORE_API_BASE_URL ?? DEFAULT_CORE).replace(/\/$/, "");
}

function isLiteralLoopbackHost(hostname: string): boolean {
  return hostname === "127.0.0.1" || hostname === "[::1]" || hostname === "::1";
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
  // F08: fail-safe — Secure on every production-like/unknown deployment; omitted only on an
  // explicit local/test profile. Issuance and clearing both read this one predicate.
  return isSecureCookieDeployment();
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
