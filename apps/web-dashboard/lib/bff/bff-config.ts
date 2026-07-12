/**
 * Server-only BFF configuration. Never expose gateway or session secrets to the browser.
 * This module is Edge-safe: env reads only, no Node-only imports.
 */
import { isProductionLikeDeployment } from "./bff-deployment-profile.ts";
import { decodeGatewaySharedSecret, readGatewaySharedSecretEnv } from "./bff-gateway-key.ts";

const DEFAULT_CORE = "http://127.0.0.1:8080";

export type BffRuntimeMode = "demo-dev" | "bff-production" | "unavailable";

export function bffRuntimeMode(): BffRuntimeMode {
  if (process.env.NODE_ENV === "production") {
    if (process.env.NEXT_PUBLIC_ORDERPILOT_DEMO_MODE === "true") {
      return "unavailable";
    }
    if (process.env.ORDERPILOT_BFF_ENABLED === "true") {
      return "bff-production";
    }
    return "unavailable";
  }
  if (process.env.ORDERPILOT_BFF_ENABLED === "true") {
    return "bff-production";
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
    if (parsed.hash) {
      return null;
    }
    if (parsed.protocol === "http:") {
      // Production-like: only literal loopback HTTP. Local/test may use explicit http Core URLs.
      if (isProductionLikeDeployment() && !isLiteralLoopbackHost(parsed.hostname)) {
        return null;
      }
    }
    return raw.replace(/\/+$/, "");
  } catch {
    return null;
  }
}

/**
 * Validated gateway shared secret (64 hex → 32 raw bytes). Returns the configured hex string when
 * valid; empty string when missing/invalid (callers fail closed).
 */
export function bffGatewaySharedSecret(): string {
  const raw = readGatewaySharedSecretEnv();
  const decoded = decodeGatewaySharedSecret(raw);
  return decoded.ok ? raw.trim() : "";
}

export function bffGatewayClockSkewSeconds(): number {
  const raw = process.env.ORDERPILOT_GATEWAY_HEADER_AUTH_CLOCK_SKEW_SECONDS ?? "300";
  const parsed = Number.parseInt(raw, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 300;
}

/** Upstream Core timeout for the BFF proxy, bounded to [1s, 120s]. */
export function bffUpstreamTimeoutMs(): number {
  const raw = process.env.ORDERPILOT_BFF_UPSTREAM_TIMEOUT_MS ?? "30000";
  const parsed = Number.parseInt(raw, 10);
  if (!Number.isFinite(parsed)) {
    return 30_000;
  }
  return Math.min(120_000, Math.max(1_000, parsed));
}

export function bffCookieSecure(): boolean {
  return process.env.NODE_ENV === "production";
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
