/**
 * Server-only BFF configuration. Never expose gateway or session secrets to the browser.
 * This module is Edge-safe: env reads only, no Node-only imports.
 */
import { isProductionLikeDeployment } from "./bff-deployment-profile.ts";

const DEFAULT_CORE = "http://localhost:8080";

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

/** Demo/dev only base URL (may fall back to localhost). Never used by the production proxy. */
export function coreApiInternalBaseUrl(): string {
  return (process.env.CORE_API_BASE_URL ?? DEFAULT_CORE).replace(/\/$/, "");
}

/**
 * Validated internal Core origin for the BFF proxy. Requires an explicit http(s) URL; a
 * production-like deployment never falls back to localhost. Returns null when invalid/missing.
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
    return raw.replace(/\/+$/, "");
  } catch {
    return null;
  }
}

export function bffSessionSecret(): string {
  return process.env.ORDERPILOT_BFF_SESSION_SECRET?.trim() ?? "";
}

export function bffGatewaySharedSecret(): string {
  return process.env.ORDERPILOT_GATEWAY_HEADER_AUTH_SHARED_SECRET?.trim() ?? "";
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

export const BFF_SESSION_COOKIE = "op_session";
export const BFF_CSRF_COOKIE = "op_csrf";
export const BFF_CSRF_HEADER = "X-OP-CSRF-Token";
