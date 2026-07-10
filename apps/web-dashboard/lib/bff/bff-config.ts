/**
 * Server-only BFF configuration. Never expose gateway or session secrets to the browser.
 */

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
  if (process.env.ORDERPILOT_DEMO_MODE === "true" || process.env.NEXT_PUBLIC_ORDERPILOT_DEMO_MODE === "true") {
    return "demo-dev";
  }
  return "demo-dev";
}

export function coreApiInternalBaseUrl(): string {
  return (process.env.CORE_API_BASE_URL ?? DEFAULT_CORE).replace(/\/$/, "");
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

export function bffCookieSecure(): boolean {
  return process.env.NODE_ENV === "production";
}

export const BFF_SESSION_COOKIE = "op_session";
export const BFF_CSRF_COOKIE = "op_csrf";
export const BFF_CSRF_HEADER = "X-OP-CSRF-Token";
